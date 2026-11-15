package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

func testConfig(t *testing.T, upstream string) config {
	t.Helper()
	parsed, err := url.Parse(upstream)
	if err != nil {
		t.Fatalf("bad upstream: %v", err)
	}
	return config{
		Addr:                          ":0",
		Upstream:                      parsed,
		MaxBodyBytes:                  1024,
		RatePerSecond:                 0,
		Burst:                         100,
		UpstreamResponseHeaderTimeout: 5 * time.Second,
	}
}

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func headerEcho(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"path":          r.URL.Path,
			"authorization": r.Header.Get("Authorization"),
			"requestId":     r.Header.Get("X-Request-Id"),
			"agentId":       r.Header.Get("X-Agent-Id"),
			"internal":      r.Header.Get("X-Internal"),
			"body":          readAll(r.Body),
		})
	}))
}

func readAll(reader io.Reader) string {
	if reader == nil {
		return ""
	}
	data, _ := io.ReadAll(reader)
	return string(data)
}

func TestHealthIsAnsweredByTheEdge(t *testing.T) {
	upstream := headerEcho(t)
	defer upstream.Close()

	edge := httptest.NewServer(newHandler(testConfig(t, upstream.URL), discardLogger()))
	defer edge.Close()

	response, err := http.Get(edge.URL + "/-/health")
	if err != nil {
		t.Fatalf("health request failed: %v", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("health status = %d, want 200", response.StatusCode)
	}
	var body map[string]string
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatalf("health body is not JSON: %v", err)
	}
	if body["status"] != "UP" {
		t.Fatalf("health body = %v, want status UP", body)
	}

	response, err = http.Post(edge.URL+"/-/health", "text/plain", strings.NewReader("x"))
	if err != nil {
		t.Fatalf("POST health failed: %v", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("POST health status = %d, want 405", response.StatusCode)
	}
}

func TestProxiesAndStripsForgeableIdentityHeaders(t *testing.T) {
	upstream := headerEcho(t)
	defer upstream.Close()

	edge := httptest.NewServer(newHandler(testConfig(t, upstream.URL), discardLogger()))
	defer edge.Close()

	request, err := http.NewRequest(http.MethodPost, edge.URL+"/mcp", strings.NewReader(`{"jsonrpc":"2.0"}`))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer apg_test")
	request.Header.Set("X-Agent-Id", "agent-forged")
	request.Header.Set("X-Internal", "trust-me")
	request.Header.Set("Content-Type", "application/json")

	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("proxy request failed: %v", err)
	}
	defer response.Body.Close()

	var echoed map[string]string
	if err := json.NewDecoder(response.Body).Decode(&echoed); err != nil {
		t.Fatalf("upstream body is not JSON: %v", err)
	}
	if echoed["path"] != "/mcp" {
		t.Errorf("path = %q, want /mcp", echoed["path"])
	}
	if echoed["authorization"] != "Bearer apg_test" {
		t.Errorf("authorization = %q, want the original bearer token", echoed["authorization"])
	}
	if echoed["agentId"] != "" {
		t.Errorf("X-Agent-Id reached the gateway as %q; a client must not be able to claim an identity", echoed["agentId"])
	}
	if echoed["internal"] != "" {
		t.Errorf("X-Internal reached the gateway as %q", echoed["internal"])
	}
	if echoed["requestId"] == "" {
		t.Error("the edge did not add a request id")
	}
	if echoed["body"] != `{"jsonrpc":"2.0"}` {
		t.Errorf("body = %q, want it forwarded unchanged", echoed["body"])
	}
}

func TestKeepsAClientSuppliedRequestID(t *testing.T) {
	upstream := headerEcho(t)
	defer upstream.Close()
	edge := httptest.NewServer(newHandler(testConfig(t, upstream.URL), discardLogger()))
	defer edge.Close()

	request, _ := http.NewRequest(http.MethodGet, edge.URL+"/admin/overview", nil)
	request.Header.Set("X-Request-Id", "caller-supplied-id")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	var echoed map[string]string
	_ = json.NewDecoder(response.Body).Decode(&echoed)
	if echoed["requestId"] != "caller-supplied-id" {
		t.Fatalf("request id = %q, want the caller's value preserved", echoed["requestId"])
	}
}

func TestStreamsResponsesWithoutBuffering(t *testing.T) {
	firstChunkWritten := make(chan struct{})
	release := make(chan struct{})

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		flusher, ok := w.(http.Flusher)
		if !ok {
			t.Error("upstream cannot flush")
			return
		}
		_, _ = w.Write([]byte("data: first\n\n"))
		flusher.Flush()
		close(firstChunkWritten)
		<-release
		_, _ = w.Write([]byte("data: second\n\n"))
		flusher.Flush()
	}))
	defer upstream.Close()

	edge := httptest.NewServer(newHandler(testConfig(t, upstream.URL), discardLogger()))
	defer edge.Close()

	response, err := http.Get(edge.URL + "/mcp")
	if err != nil {
		t.Fatalf("stream request failed: %v", err)
	}
	defer response.Body.Close()

	// Read the first event while the upstream is still blocked on release. If the edge buffered,
	// this read would not return until the handler finished.
	buffer := make([]byte, 64)
	readDone := make(chan string, 1)
	go func() {
		n, _ := response.Body.Read(buffer)
		readDone <- string(buffer[:n])
	}()

	select {
	case <-firstChunkWritten:
	case <-time.After(2 * time.Second):
		t.Fatal("upstream never wrote its first chunk")
	}

	select {
	case chunk := <-readDone:
		if !strings.Contains(chunk, "first") {
			t.Fatalf("first chunk = %q, want the first event", chunk)
		}
	case <-time.After(2 * time.Second):
		close(release)
		t.Fatal("the edge buffered the stream: the first event never arrived")
	}
	close(release)
}

func TestRateLimitIsPerCredential(t *testing.T) {
	upstream := headerEcho(t)
	defer upstream.Close()

	cfg := testConfig(t, upstream.URL)
	cfg.RatePerSecond = 0.001 // effectively no refill during the test
	cfg.Burst = 2
	edge := httptest.NewServer(newHandler(cfg, discardLogger()))
	defer edge.Close()

	call := func(token string) *http.Response {
		request, _ := http.NewRequest(http.MethodGet, edge.URL+"/admin/overview", nil)
		request.Header.Set("Authorization", "Bearer "+token)
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatalf("request failed: %v", err)
		}
		_, _ = io.Copy(io.Discard, response.Body)
		response.Body.Close()
		return response
	}

	for i := 0; i < 2; i++ {
		if response := call("apg_agent_one"); response.StatusCode != http.StatusOK {
			t.Fatalf("request %d status = %d, want 200", i, response.StatusCode)
		}
	}
	limited := call("apg_agent_one")
	if limited.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("third request status = %d, want 429", limited.StatusCode)
	}
	if limited.Header.Get("Retry-After") == "" {
		t.Error("429 response has no Retry-After header")
	}

	// A different credential has its own budget; one noisy agent must not starve another.
	if response := call("apg_agent_two"); response.StatusCode != http.StatusOK {
		t.Fatalf("second credential status = %d, want 200", response.StatusCode)
	}
}

func TestDeclaredOversizedBodyIsRejectedBeforeForwarding(t *testing.T) {
	forwarded := false
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		forwarded = true
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	cfg := testConfig(t, upstream.URL)
	cfg.MaxBodyBytes = 32
	edge := httptest.NewServer(newHandler(cfg, discardLogger()))
	defer edge.Close()

	response, err := http.Post(edge.URL+"/mcp", "application/json",
		bytes.NewReader(bytes.Repeat([]byte("a"), 256)))
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("status = %d, want 413", response.StatusCode)
	}
	if forwarded {
		t.Fatal("an oversized body reached the upstream")
	}

	var body map[string]any
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatalf("413 body is not JSON: %v", err)
	}
	if body["code"] != "BODY_TOO_LARGE" {
		t.Fatalf("413 code = %v, want BODY_TOO_LARGE", body["code"])
	}
}

func TestUnreachableUpstreamBecomes502(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	upstreamURL := upstream.URL
	upstream.Close() // nothing is listening now

	edge := httptest.NewServer(newHandler(testConfig(t, upstreamURL), discardLogger()))
	defer edge.Close()

	response, err := http.Get(edge.URL + "/mcp")
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", response.StatusCode)
	}
	var body map[string]any
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatalf("502 body is not JSON: %v", err)
	}
	if body["code"] != "UPSTREAM_UNAVAILABLE" {
		t.Fatalf("502 code = %v, want UPSTREAM_UNAVAILABLE", body["code"])
	}
}

func TestLimiterRefillsOverTime(t *testing.T) {
	limiter := newRateLimiter(1, 2)
	frozen := time.Unix(1_700_000_000, 0)
	limiter.now = func() time.Time { return frozen }

	if ok, _ := limiter.allow("k"); !ok {
		t.Fatal("first request should be allowed")
	}
	if ok, _ := limiter.allow("k"); !ok {
		t.Fatal("second request should be allowed")
	}
	if ok, wait := limiter.allow("k"); ok || wait <= 0 {
		t.Fatalf("third request = (%v, %v), want denied with a wait", ok, wait)
	}

	frozen = frozen.Add(2 * time.Second)
	if ok, _ := limiter.allow("k"); !ok {
		t.Fatal("after refilling, the request should be allowed")
	}
	if limiter.size() != 1 {
		t.Fatalf("limiter tracks %d keys, want 1", limiter.size())
	}

	frozen = frozen.Add(time.Hour)
	limiter.forget(10 * time.Minute)
	if limiter.size() != 0 {
		t.Fatalf("idle keys were not dropped: %d left", limiter.size())
	}
}

func TestRateLimiterIsSafeUnderConcurrency(t *testing.T) {
	limiter := newRateLimiter(0.001, 50)
	var wg sync.WaitGroup
	allowed := make(chan bool, 200)
	for i := 0; i < 200; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ok, _ := limiter.allow("shared")
			allowed <- ok
		}()
	}
	wg.Wait()
	close(allowed)

	granted := 0
	for ok := range allowed {
		if ok {
			granted++
		}
	}
	if granted != 50 {
		t.Fatalf("granted %d requests, want exactly the burst of 50", granted)
	}
}

func TestConfigValidation(t *testing.T) {
	cases := []struct {
		name    string
		env     map[string]string
		wantErr string
	}{
		{"bad upstream URL", map[string]string{"EDGE_UPSTREAM": "://nope"}, "not a URL"},
		{"wrong scheme", map[string]string{"EDGE_UPSTREAM": "ftp://example.com"}, "must be http or https"},
		{"non-numeric body limit", map[string]string{"EDGE_MAX_BODY_BYTES": "big"}, "must be an integer"},
		{"negative rate", map[string]string{"EDGE_RATE_PER_SECOND": "-1"}, "must not be negative"},
		{"zero burst", map[string]string{"EDGE_RATE_BURST": "0"}, "at least 1"},
		{"bad duration", map[string]string{"EDGE_UPSTREAM_HEADER_TIMEOUT": "soon"}, "must be a duration"},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			for key, value := range testCase.env {
				t.Setenv(key, value)
			}
			_, err := loadConfig()
			if err == nil {
				t.Fatalf("loadConfig accepted %v", testCase.env)
			}
			if !strings.Contains(err.Error(), testCase.wantErr) {
				t.Fatalf("error = %q, want it to mention %q", err, testCase.wantErr)
			}
		})
	}
}

func TestConfigDefaults(t *testing.T) {
	t.Setenv("EDGE_UPSTREAM", "")
	cfg, err := loadConfig()
	if err != nil {
		t.Fatalf("defaults should be valid: %v", err)
	}
	if cfg.Addr != ":8088" {
		t.Errorf("addr = %q, want :8088", cfg.Addr)
	}
	if cfg.MaxBodyBytes != 1<<20 {
		t.Errorf("max body = %d, want 1048576", cfg.MaxBodyBytes)
	}
	if cfg.RatePerSecond != 20 || cfg.Burst != 40 {
		t.Errorf("rate = %v burst = %d, want 20/40", cfg.RatePerSecond, cfg.Burst)
	}
}
