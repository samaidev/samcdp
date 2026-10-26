package main

// aitun_embed_linux_amd64.go — v1.4.7: bundle the aitun client INSIDE the
// Linux executable (same policy as Windows/macOS builds).

import _ "embed"

//go:embed aitunbin/aitun-client-linux-amd64
var aitunEmbedded []byte
