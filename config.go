package main

// config.go — 配置持久化（支持多 profile）

import (
	"crypto/rand"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// Profile 单个浏览器 profile 配置
type Profile struct {
	Name    string `json:"name"`     // profile 名称，如 "agent-1"
	Port    int    `json:"port"`     // CDP 调试端口
	Enabled bool   `json:"enabled"`  // 是否启用
}

type Config struct {
	mu       sync.Mutex
	filePath string

	AitunKey  string    `json:"aitun_key"`
	AuthToken string    `json:"auth_token"`
	Lang      string    `json:"lang"`
	Profiles  []Profile `json:"profiles"`
}

var cfg *Config

// configDir 返回 cdpclient 配置目录
func configDir() string {
	base := os.Getenv("APPDATA")
	if base == "" {
		base, _ = os.UserConfigDir()
	}
	return filepath.Join(base, "cdpclient")
}

func initConfig() {
	dir := configDir()
	os.MkdirAll(dir, 0755)
	cfg = &Config{
		filePath: filepath.Join(dir, "config.json"),
	}
	cfg.load()

	// 首次启动自动生成 auth token
	if cfg.AuthToken == "" {
		cfg.AuthToken = generateAuthToken()
		cfg.save()
	}

	// 如果没有 profiles，创建默认 profile（单 Chrome 实例）
	if len(cfg.Profiles) == 0 {
		cfg.Profiles = []Profile{
			{Name: "default", Port: 9334, Enabled: true},
		}
		cfg.save()
	}

	if cfg.Lang == "en" {
		setLang(LangEN)
	} else {
		setLang(LangZH)
	}
}

func (c *Config) load() {
	data, err := os.ReadFile(c.filePath)
	if err != nil {
		return
	}
	var raw struct {
		AitunKey  string    `json:"aitun_key"`
		AuthToken string    `json:"auth_token"`
		Lang      string    `json:"lang"`
		Profiles  []Profile `json:"profiles"`
	}
	if json.Unmarshal(data, &raw) == nil {
		c.AitunKey = raw.AitunKey
		c.AuthToken = raw.AuthToken
		c.Lang = raw.Lang
		if len(raw.Profiles) > 0 {
			c.Profiles = raw.Profiles
		}
	}
}

func (c *Config) save() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	dir := filepath.Dir(c.filePath)
	os.MkdirAll(dir, 0755)
	raw := struct {
		AitunKey  string    `json:"aitun_key"`
		AuthToken string    `json:"auth_token"`
		Lang      string    `json:"lang"`
		Profiles  []Profile `json:"profiles"`
	}{
		AitunKey:  c.AitunKey,
		AuthToken: c.AuthToken,
		Lang:      string(currentLang),
		Profiles:  c.Profiles,
	}
	data, err := json.MarshalIndent(raw, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(c.filePath, data, 0644)
}

func (c *Config) GetAitunKey() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.AitunKey
}

func (c *Config) SetAitunKey(key string) {
	c.mu.Lock()
	c.AitunKey = key
	c.mu.Unlock()
	c.save()
}

func (c *Config) GetAuthToken() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.AuthToken
}

func (c *Config) RegenerateAuthToken() string {
	c.mu.Lock()
	c.AuthToken = generateAuthToken()
	c.mu.Unlock()
	c.save()
	return c.AuthToken
}

// GetProfiles 返回所有 profile 的副本
func (c *Config) GetProfiles() []Profile {
	c.mu.Lock()
	defer c.mu.Unlock()
	result := make([]Profile, len(c.Profiles))
	copy(result, c.Profiles)
	return result
}

// GetEnabledProfiles 返回所有启用的 profile
func (c *Config) GetEnabledProfiles() []Profile {
	c.mu.Lock()
	defer c.mu.Unlock()
	var result []Profile
	for _, p := range c.Profiles {
		if p.Enabled {
			result = append(result, p)
		}
	}
	return result
}

// AddProfile 添加一个新 profile，自动分配端口
func (c *Config) AddProfile(name string) Profile {
	c.mu.Lock()
	// Chrome 端口从 9334 起（9333 是 CDP 路由代理端口）
	maxPort := 9333
	for _, p := range c.Profiles {
		if p.Port > maxPort {
			maxPort = p.Port
		}
	}
	newProfile := Profile{Name: name, Port: maxPort + 1, Enabled: true}
	c.Profiles = append(c.Profiles, newProfile)
	c.mu.Unlock()
	c.save()
	return newProfile
}

// RemoveProfile 按 name 删除 profile
func (c *Config) RemoveProfile(name string) {
	c.mu.Lock()
	for i, p := range c.Profiles {
		if p.Name == name {
			c.Profiles = append(c.Profiles[:i], c.Profiles[i+1:]...)
			break
		}
	}
	c.mu.Unlock()
	c.save()
}

// SetProfileEnabled 设置 profile 启用/禁用
func (c *Config) SetProfileEnabled(name string, enabled bool) {
	c.mu.Lock()
	for i, p := range c.Profiles {
		if p.Name == name {
			c.Profiles[i].Enabled = enabled
			break
		}
	}
	c.mu.Unlock()
	c.save()
}

// generateAuthToken 生成随机鉴权 token (32 字符)
func generateAuthToken() string {
	const chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, 32)
	rand.Read(b)
	for i := range b {
		b[i] = chars[int(b[i])%len(chars)]
	}
	return string(b)
}
