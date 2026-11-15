package main

import (
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// config is everything the edge needs. It is read once at start-up: an edge that reloads config
// while a payment is in flight is an edge nobody can reason about.
type config struct {
	// Addr is the listen address, e.g. ":8088".
	Addr string
	// Upstream is the base URL of the gateway, e.g. "http://gateway:8080".
	Upstream *url.URL
	// MaxBodyBytes caps a request body. MCP calls are small; a megabyte is already generous.
	MaxBodyBytes int64
	// RatePerSecond and Burst configure the per-credential token bucket. Zero disables limiting.
	RatePerSecond float64
	Burst         int
	// UpstreamResponseHeaderTimeout bounds how long the gateway may take to start answering.
	// It deliberately does not bound the body: an MCP response can be a long-lived stream.
	UpstreamResponseHeaderTimeout time.Duration
}

func loadConfig() (config, error) {
	addr := envOr("EDGE_ADDR", ":8088")
	upstreamRaw := envOr("EDGE_UPSTREAM", "http://localhost:8080")

	upstream, err := url.Parse(upstreamRaw)
	if err != nil {
		return config{}, fmt.Errorf("EDGE_UPSTREAM %q is not a URL: %w", upstreamRaw, err)
	}
	if upstream.Scheme != "http" && upstream.Scheme != "https" {
		return config{}, fmt.Errorf("EDGE_UPSTREAM must be http or https, got %q", upstream.Scheme)
	}
	if upstream.Host == "" {
		return config{}, fmt.Errorf("EDGE_UPSTREAM %q has no host", upstreamRaw)
	}

	maxBody, err := envInt64("EDGE_MAX_BODY_BYTES", 1<<20)
	if err != nil {
		return config{}, err
	}
	if maxBody <= 0 {
		return config{}, fmt.Errorf("EDGE_MAX_BODY_BYTES must be positive, got %d", maxBody)
	}

	rate, err := envFloat("EDGE_RATE_PER_SECOND", 20)
	if err != nil {
		return config{}, err
	}
	if rate < 0 {
		return config{}, fmt.Errorf("EDGE_RATE_PER_SECOND must not be negative, got %v", rate)
	}
	burst, err := envInt64("EDGE_RATE_BURST", 40)
	if err != nil {
		return config{}, err
	}
	if burst < 1 {
		return config{}, fmt.Errorf("EDGE_RATE_BURST must be at least 1, got %d", burst)
	}

	headerTimeout, err := envDuration("EDGE_UPSTREAM_HEADER_TIMEOUT", 30*time.Second)
	if err != nil {
		return config{}, err
	}

	return config{
		Addr:                          addr,
		Upstream:                      upstream,
		MaxBodyBytes:                  maxBody,
		RatePerSecond:                 rate,
		Burst:                         int(burst),
		UpstreamResponseHeaderTimeout: headerTimeout,
	}, nil
}

func envOr(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func envInt64(key string, fallback int64) (int64, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("%s must be an integer, got %q", key, raw)
	}
	return value, nil
}

func envFloat(key string, fallback float64) (float64, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return 0, fmt.Errorf("%s must be a number, got %q", key, raw)
	}
	return value, nil
}

func envDuration(key string, fallback time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}
	value, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("%s must be a duration such as 30s, got %q", key, raw)
	}
	if value <= 0 {
		return 0, fmt.Errorf("%s must be positive, got %v", key, value)
	}
	return value, nil
}
