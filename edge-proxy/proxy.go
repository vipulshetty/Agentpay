package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"strconv"
	"time"
)

// newHandler builds the edge's HTTP handler.
//
// Responsibilities, in the order a request meets them:
//  1. internal endpoints (/-/health) are answered here, never proxied;
//  2. request bodies are capped, so a client cannot make the gateway hold an unbounded body;
//  3. rate limiting is per credential, because one runaway agent must not starve the others;
//  4. identity headers a client could forge are removed before forwarding;
//  5. the request is proxied without buffering, so an MCP stream is passed through as it arrives.
func newHandler(cfg config, logger *slog.Logger) http.Handler {
	target := *cfg.Upstream
	proxy := httputil.NewSingleHostReverseProxy(&target)

	// FlushInterval -1 flushes every write immediately: an MCP response may be an SSE stream, and
	// a buffering proxy would turn a live stream into a long silence.
	proxy.FlushInterval = -1
	proxy.Transport = &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		MaxIdleConns:          128,
		MaxIdleConnsPerHost:   64,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: time.Second,
		// Bounds the wait for the first byte only. Bounding the whole response would cut streams.
		ResponseHeaderTimeout: cfg.UpstreamResponseHeaderTimeout,
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeJSONError(w, http.StatusRequestEntityTooLarge, "BODY_TOO_LARGE",
				"request body exceeds the edge limit")
			return
		}
		logger.Error("upstream request failed", "path", r.URL.Path, "error", err)
		writeJSONError(w, http.StatusBadGateway, "UPSTREAM_UNAVAILABLE", "the gateway is not reachable")
	}

	originalDirector := proxy.Director
	proxy.Director = func(r *http.Request) {
		originalDirector(r)
		// A client must not be able to claim an identity. The gateway authenticates the bearer
		// token itself; anything that looks like a trusted identity header is dropped here.
		for _, header := range []string{"X-Agent-Id", "X-Agent", "X-User-Id", "X-Internal", "X-Forwarded-User"} {
			r.Header.Del(header)
		}
		if r.Header.Get("X-Request-Id") == "" {
			r.Header.Set("X-Request-Id", newRequestID())
		}
		r.Header.Set("X-Forwarded-Host", r.Host)
	}

	limiter := newRateLimiter(cfg.RatePerSecond, cfg.Burst)

	mux := http.NewServeMux()
	mux.HandleFunc("/-/health", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			w.Header().Set("Allow", http.MethodGet)
			writeJSONError(w, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "health is a GET")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"UP"}` + "\n"))
	})
	mux.Handle("/", limitBody(cfg.MaxBodyBytes, rateLimit(limiter, proxy)))

	return mux
}

// limitBody caps how much a request may send. A declared length over the cap is rejected before a
// byte is forwarded; a chunked body is cut off by MaxBytesReader and mapped to 413 by the proxy's
// error handler.
func limitBody(max int64, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ContentLength > max {
			writeJSONError(w, http.StatusRequestEntityTooLarge, "BODY_TOO_LARGE",
				"request body exceeds the edge limit of "+strconv.FormatInt(max, 10)+" bytes")
			return
		}
		if r.Body != nil {
			r.Body = http.MaxBytesReader(w, r.Body, max)
		}
		next.ServeHTTP(w, r)
	})
}

// rateLimit keys the allowance on the credential when there is one, and on the peer address
// otherwise. Keying on the credential means one agent cannot exhaust another's budget, and an
// unauthenticated flood is still bounded.
func rateLimit(limiter *rateLimiter, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		allowed, wait := limiter.allow(credentialKey(r))
		if !allowed {
			seconds := int(wait.Seconds())
			if seconds < 1 {
				seconds = 1
			}
			w.Header().Set("Retry-After", strconv.Itoa(seconds))
			writeJSONError(w, http.StatusTooManyRequests, "RATE_LIMITED",
				"too many requests for this credential; retry after "+strconv.Itoa(seconds)+"s")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func credentialKey(r *http.Request) string {
	header := r.Header.Get("Authorization")
	if header == "" {
		return "ip:" + remoteIP(r)
	}
	// The token itself is never stored or logged; a short digest is enough to bucket on.
	return "cred:" + digest(header)
}

func remoteIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func digest(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:8])
}

func newRequestID() string {
	var buf [8]byte
	if _, err := rand.Read(buf[:]); err != nil {
		// Falling back to a timestamp keeps correlation working; the id is not a secret.
		return "req-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	}
	return "req-" + hex.EncodeToString(buf[:])
}

func writeJSONError(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"code":    code,
		"message": message,
		"status":  status,
	})
}
