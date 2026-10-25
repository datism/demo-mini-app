package redisclient

import (
	"context"
	"log"
	"os"
	"time"

	"github.com/redis/go-redis/v9"
)

var (
	client *redis.Client
	ctx    = context.Background()
)

// Init initializes the Redis client using Sentinel if available, or direct connection
func Init() {
	sentinelHost := os.Getenv("REDIS_SENTINEL_HOST")
	if sentinelHost == "" {
		sentinelHost = "redis-sentinel-service.demo-mini-app.svc.cluster.local:26379"
	} else if os.Getenv("REDIS_SENTINEL_PORT") != "" {
		sentinelHost = sentinelHost + ":" + os.Getenv("REDIS_SENTINEL_PORT")
	}

	client = redis.NewFailoverClient(&redis.FailoverOptions{
		MasterName:    "mymaster",
		SentinelAddrs: []string{sentinelHost},
		Password:      "",
		DB:            0,
		DialTimeout:   5 * time.Second,
		ReadTimeout:   3 * time.Second,
		WriteTimeout:  3 * time.Second,
	})

	// Ping to test connection
	_, err := client.Ping(ctx).Result()
	if err != nil {
		log.Printf("[REDIS] Failed to connect to Sentinel at %s: %v. Retrying with direct master...", sentinelHost, err)
		
		masterHost := os.Getenv("REDIS_HOST")
		if masterHost == "" {
			masterHost = "redis-0.redis-service.demo-mini-app.svc.cluster.local:6379"
		}
		client = redis.NewClient(&redis.Options{
			Addr:     masterHost,
			Password: "",
			DB:       0,
		})
		
		_, err = client.Ping(ctx).Result()
		if err != nil {
			log.Printf("[REDIS] ❌ FATAL: Could not connect to Redis at all: %v", err)
		} else {
			log.Printf("[REDIS] ✅ Connected to Redis Master Directly at %s", masterHost)
		}
	} else {
		log.Printf("[REDIS] ✅ Connected to Redis via Sentinel (%s)", sentinelHost)
	}
}

// Set stores a key-value pair with TTL (in seconds)
func Set(key string, value string, ttlSeconds int) error {
	if client == nil {
		return nil
	}
	return client.Set(ctx, key, value, time.Duration(ttlSeconds)*time.Second).Err()
}

// Get retrieves a value by key
func Get(key string) (string, error) {
	if client == nil {
		return "", redis.Nil
	}
	return client.Get(ctx, key).Result()
}

// Del removes a key
func Del(key string) error {
	if client == nil {
		return nil
	}
	return client.Del(ctx, key).Err()
}
