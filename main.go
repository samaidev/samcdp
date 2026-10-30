package main

// main.go — cdpclient 入口 + 系统托盘（多 profile）

import (
	"fmt"
	"os"
	"runtime"

	"github.com/energye/systray"
)

// 托盘菜单项
var (
	mRun       *systray.MenuItem
	mStop      *systray.MenuItem
	mLang      *systray.MenuItem
	mAutoStart *systray.MenuItem
	mCDPAddr   *systray.MenuItem
	mSettings  *systray.MenuItem
	mQuit      *systray.MenuItem
)

// appVersion 桌面版版本号（设置页 h1 与 /api/status 暴露，远程排障可识别构建）
// v1.4.9: 模式透明化 —— Key 明文展示、实际地址形式与 Key 设置一致性提示、
// Key 变更即清除持久化身份（杜绝"没设 Key 却拿到注册子域名地址"）
const appVersion = "1.4.9"

func main() {
	// -settings flag: 直接打开设置页面（用于测试或无桌面环境）
	if len(os.Args) > 1 && os.Args[1] == "-settings" {
		initConfig()
		go func() {
			svc.Start()
		}()
		url, err := startSettingsServer()
		if err != nil {
			fmt.Fprintf(os.Stderr, "settings server error: %v\n", err)
			os.Exit(1)
		}
		fmt.Println(url)
		openBrowser(url)
		select {}
	}

	// Linux 无桌面环境检测: 如果没有 DISPLAY，直接进入 headless 服务模式
	if runtime.GOOS == "linux" && os.Getenv("DISPLAY") == "" {
		fmt.Println("[cdpclient] No DISPLAY detected, running in headless service mode")
		initConfig()
		ensureAitunBinary()
		if err := svc.Start(); err != nil {
			fmt.Fprintf(os.Stderr, "service start error: %v\n", err)
			os.Exit(1)
		}
		// 打印远程地址
		url := svc.GetRemoteAddress()
		token := svc.GetToken()
		if url != "" {
			fmt.Printf("Remote URL: %s?token=%s\n", url, token)
		}
		// 保持运行
		select {}
	}

	initConfig()
	ensureAitunBinary()
	systray.Run(onTrayReady, onTrayExit)
}

func onTrayReady() {
	systray.SetIcon(trayIconBytes())
	systray.SetTitle(t("tray.tooltip"))
	systray.SetTooltip(t("tray.tooltip"))

	systray.SetOnClick(func(menu systray.IMenu) {
		go openSettings()
	})

	mRun = systray.AddMenuItem(t("tray.run"), t("tray.run"))
	mStop = systray.AddMenuItem(t("tray.stop"), t("tray.stop"))
	systray.AddSeparator()

	mLang = systray.AddMenuItem(t("tray.lang"), t("tray.lang"))
	systray.AddSeparator()

	mAutoStart = systray.AddMenuItem(t("tray.autoStart"), t("tray.autoStart"))
	systray.AddSeparator()

	mCDPAddr = systray.AddMenuItem(t("tray.cdpAddr"), t("tray.cdpAddr"))
	mSettings = systray.AddMenuItem(t("tray.settings"), t("tray.settings"))
	systray.AddSeparator()

	mQuit = systray.AddMenuItem(t("tray.quit"), t("tray.quit"))

	refreshMenuState()

	// 设置页服务常驻本地（127.0.0.1），菜单点击秒开，本地脚本亦可查询状态
	go startSettingsServer()

	// 自动启动服务（托盘模式也需要自动启动）
	go func() {
		if err := svc.Start(); err != nil {
			fmt.Fprintf(os.Stderr, "auto start service error: %v\n", err)
		} else {
			fmt.Println("[cdpclient] service auto-started")
		}
		refreshMenuState()
	}()

	mRun.Click(func() {
		go func() {
			stopSettingsServer()
			if err := svc.Start(); err != nil {
				msg := t("toast.startFailed") + err.Error()
				winToast(msg)
				if isChromeNotFoundError(err) {
					winToast(t("toast.chromeNotFound"))
					openBrowser(chromeDownloadURL())
				}
			} else {
				winToast(t("toast.started"))
			}
			refreshMenuState()
		}()
	})

	mStop.Click(func() {
		go func() {
			svc.Stop()
			winToast(t("toast.stopped"))
			refreshMenuState()
		}()
	})

	mLang.Click(func() {
		toggleLang()
		cfg.Lang = string(currentLang)
		cfg.save()
		refreshMenuLabels()
	})

	mAutoStart.Click(func() {
		go func() {
			enabled, _ := IsAutoStartEnabled()
			if enabled {
				DisableAutoStart()
				winToast(t("toast.autoStartOff"))
			} else {
				EnableAutoStart()
				winToast(t("toast.autoStartOn"))
			}
			refreshMenuState()
		}()
	})

	mCDPAddr.Click(func() {
		go func() {
			if !svc.IsRunning() {
				winToast(t("toast.notRunning"))
				return
			}
			// 地址规则与设置页一致：隧道在线→隧道地址；否则→局域网地址（唯一可达入口）
			addr := svc.GetCopyAddress()
			if token := svc.GetToken(); token != "" {
				addr += "?token=" + token
			}
			if err := copyToClipboard(addr); err != nil {
				debugLog("[tray] copy failed: " + err.Error())
				winToast(t("toast.copyFailed") + err.Error())
				return
			}
			debugLog("[tray] copied: " + addr)
			winToast(t("toast.copied") + " " + addr)
		}()
	})

	mSettings.Click(func() {
		go openSettings()
	})

	mQuit.Click(func() {
		svc.Stop()
		systray.Quit()
		// macOS: systray.Quit() 调用 [NSApp terminate:] 但不会移除状态栏图标
		// 也不会退出进程（如果还有其他 goroutine 在运行），所以强制退出
		os.Exit(0)
	})
}

func onTrayExit() {
	svc.Stop()
}

func refreshMenuState() {
	if mRun == nil {
		return
	}
	if svc.IsRunning() {
		mRun.Hide()
		mStop.Show()
	} else {
		mRun.Show()
		mStop.Hide()
	}

	enabled, _ := IsAutoStartEnabled()
	if enabled {
		mAutoStart.Check()
		mAutoStart.SetTitle(t("tray.cancelAuto"))
	} else {
		mAutoStart.Uncheck()
		mAutoStart.SetTitle(t("tray.autoStart"))
	}

	// CDP地址菜单项
	if svc.IsRunning() && svc.GetRemoteAddress() != "" {
		mCDPAddr.SetTitle(t("tray.cdpAddr"))
	} else {
		mCDPAddr.SetTitle(t("tray.cdpAddr"))
	}
}

func refreshMenuLabels() {
	mRun.SetTitle(t("tray.run"))
	mStop.SetTitle(t("tray.stop"))
	mLang.SetTitle(t("tray.lang"))
	mSettings.SetTitle(t("tray.settings"))
	mQuit.SetTitle(t("tray.quit"))
	systray.SetTitle(t("tray.tooltip"))
	systray.SetTooltip(t("tray.tooltip"))
	refreshMenuState()
}

func isChromeNotFoundError(err error) bool {
	return err != nil && contains(err.Error(), "chrome not found")
}

func contains(s, sub string) bool {
	for i := 0; i <= len(s)-len(sub); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
