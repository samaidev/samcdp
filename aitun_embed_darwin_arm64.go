package main

// aitun_embed_darwin_arm64.go — v1.4.10: bundle the aitun client INSIDE the
// darwin/arm64 build (Apple Silicon). v1.4.7 shipped only darwin/amd64, so the
// arm64 binary was 6.4MB without a client and had to fall back to disk lookup
// (pip install aitun) or the aitun.cc download; embedding restores the
// zero-network-dependency guarantee on par with the other platforms.
// Filename-based build constraint: darwin + arm64 only.

import _ "embed"

//go:embed aitunbin/aitun-client-darwin-arm64
var aitunEmbedded []byte
