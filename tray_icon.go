package main

// tray_icon.go — 托盘图标 (程序内生成 ICO/PNG)

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/png"
	"runtime"
)

// trayIconBytes 返回托盘图标字节
func trayIconBytes() []byte {
	// 先尝试读取外部图标文件
	if exe, err := osExecutable(); err == nil {
		dir := filepathDir(exe)
		// Windows: ICO, Linux/Mac: PNG
		var iconFile string
		if runtime.GOOS == "windows" {
			iconFile = "appicon.ico"
		} else {
			iconFile = "appicon.png"
		}
		if data := readFileIfExists(filepathJoin(dir, iconFile)); len(data) > 0 {
			return data
		}
	}
	// 生成内置图标
	if runtime.GOOS == "windows" {
		return generateICO()
	}
	return generatePNG()
}

// generatePNG 生成 32x32 橙色背景 + 白色 "C" PNG 图标
func generatePNG() []byte {
	const s = 32
	img := image.NewRGBA(image.Rect(0, 0, s, s))

	orange := color.RGBA{R: 0xFF, G: 0x8C, B: 0x00, A: 255}
	for y := 0; y < s; y++ {
		for x := 0; x < s; x++ {
			img.Set(x, y, orange)
		}
	}

	white := color.RGBA{R: 0xFF, G: 0xFF, B: 0xFF, A: 255}
	cPattern := [16][16]int{
		{0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0},
		{0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
		{0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
		{0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0},
	}
	for y := 0; y < 16; y++ {
		for x := 0; x < 16; x++ {
			if cPattern[y][x] == 1 {
				img.Set(8+x, 8+y, white)
			}
		}
	}

	var buf bytes.Buffer
	png.Encode(&buf, img)
	return buf.Bytes()
}

// generateICO 程序内生成 32x32 橙色背景 + 白色 "C" 图标 (Windows ICO)
func generateICO() []byte {
	const s = 32
	img := image.NewRGBA(image.Rect(0, 0, s, s))

	// 橙色背景
	orange := color.RGBA{R: 0xFF, G: 0x8C, B: 0x00, A: 255}
	for y := 0; y < s; y++ {
		for x := 0; x < s; x++ {
			img.Set(x, y, orange)
		}
	}

	// 绘制白色 "C" — 用像素点阵 (16x16 区域居中)
	// C 的形状：顶部横、左竖、底部横
	white := color.RGBA{R: 0xFF, G: 0xFF, B: 0xFF, A: 255}
	// 字母 C 的像素点阵 (1=白, 0=橙)
	// 16x16 网格，从 (8,8) 开始
	cPattern := [16][16]int{
		{0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0},
		{0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
		{0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		{0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0},
		{0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0},
	}
	for y := 0; y < 16; y++ {
		for x := 0; x < 16; x++ {
			if cPattern[y][x] == 1 {
				img.Set(8+x, 8+y, white)
			}
		}
	}

	// 编码 PNG
	var pngBuf bytes.Buffer
	png.Encode(&pngBuf, img)
	pngData := pngBuf.Bytes()

	// 构建 ICO (PNG 格式条目)
	// ICO header: 6 bytes
	// Directory entry: 16 bytes
	// PNG data
	header := make([]byte, 6)
	binary.LittleEndian.PutUint16(header[0:], 0) // reserved
	binary.LittleEndian.PutUint16(header[2:], 1) // type = icon
	binary.LittleEndian.PutUint16(header[4:], 1) // count = 1

	entry := make([]byte, 16)
	entry[0] = byte(s)                                             // width
	entry[1] = byte(s)                                             // height
	entry[2] = 0                                                   // colors
	entry[3] = 0                                                   // reserved
	binary.LittleEndian.PutUint16(entry[4:], 1)                    // planes
	binary.LittleEndian.PutUint16(entry[6:], 32)                   // bpp
	binary.LittleEndian.PutUint32(entry[8:], uint32(len(pngData))) // size
	binary.LittleEndian.PutUint32(entry[12:], uint32(6+16))        // offset

	var buf bytes.Buffer
	buf.Write(header)
	buf.Write(entry)
	buf.Write(pngData)
	return buf.Bytes()
}

// 辅助函数 (避免额外 import)
func osExecutable() (string, error) {
	return osExec()
}

func filepathDir(p string) string {
	return filepathDirImpl(p)
}

func readFileIfExists(p string) []byte {
	return readFileImpl(p)
}

func filepathJoin(elem ...string) string {
	return filepathJoinImpl(elem...)
}
