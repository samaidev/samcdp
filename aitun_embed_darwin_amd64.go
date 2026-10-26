package main

// aitun_embed_darwin_amd64.go — v1.4.7: bundle the aitun client INSIDE the
// macOS executable (same policy as Windows/Linux builds).

import _ "embed"

//go:embed aitunbin/aitun-client-darwin-amd64
var aitunEmbedded []byte
