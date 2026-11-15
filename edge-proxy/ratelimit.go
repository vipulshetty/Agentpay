package main

import (
	"sync"
	"time"
)

// bucket is one credential's allowance: tokens refill continuously at rate tokens per second up
// to burst. Continuous refill means a client cannot save up an unbounded allowance by idling.
type bucket struct {
	tokens   float64
	lastFill time.Time
}

// rateLimiter is a small in-memory token bucket per key.
//
// It is intentionally per process: the edge is one hop, and a distributed limiter would need a
// shared store that this project does not run. With more than one edge instance the effective
// limit multiplies, which is a known, stated limitation rather than an accident.
type rateLimiter struct {
	mu      sync.Mutex
	rate    float64
	burst   float64
	buckets map[string]*bucket
	now     func() time.Time
}

func newRateLimiter(rate float64, burst int) *rateLimiter {
	return &rateLimiter{
		rate:    rate,
		burst:   float64(burst),
		buckets: make(map[string]*bucket),
		now:     time.Now,
	}
}

// allow consumes one token for key, reporting whether the request may proceed and, when it may
// not, how long the caller should wait before retrying.
func (l *rateLimiter) allow(key string) (bool, time.Duration) {
	if l.rate <= 0 {
		return true, 0
	}
	l.mu.Lock()
	defer l.mu.Unlock()

	now := l.now()
	entry, ok := l.buckets[key]
	if !ok {
		entry = &bucket{tokens: l.burst, lastFill: now}
		l.buckets[key] = entry
	} else if elapsed := now.Sub(entry.lastFill).Seconds(); elapsed > 0 {
		entry.tokens += elapsed * l.rate
		if entry.tokens > l.burst {
			entry.tokens = l.burst
		}
		entry.lastFill = now
	}

	if entry.tokens >= 1 {
		entry.tokens--
		return true, 0
	}
	wait := time.Duration((1-entry.tokens)/l.rate*float64(time.Second)) + time.Millisecond
	return false, wait
}

// forget drops keys that have refilled fully, so a long-running edge does not accumulate one
// entry per credential forever.
func (l *rateLimiter) forget(idleFor time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	for key, entry := range l.buckets {
		if now.Sub(entry.lastFill) > idleFor {
			delete(l.buckets, key)
		}
	}
}

func (l *rateLimiter) size() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.buckets)
}
