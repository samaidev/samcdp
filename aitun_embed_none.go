//go:build !((windows && amd64) || (linux && amd64) || (darwin && amd64))

package main

// aitun_embed_none.go — fallback for platforms without a bundled client
// (e.g. darwin/arm64 if we ever ship it without preparing a binary):
// resolution falls back to on-disk search + aitun.cc download (v1.4.6 path).

// aitunEmbedded is nil → resolveAitunBinary skips the extraction step.
var aitunEmbedded []byte
