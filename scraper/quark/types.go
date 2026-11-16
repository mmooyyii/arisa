// Package quark 是夸克网盘的客户端: 网页端 (Cookie 登录) 和 TV 端开放接口.
package quark

import "errors"

// File 是两套接口共用的文件模型. JSON 字段名和旧版 (Kotlin) 的扫描缓存一致, 不能改.
type File struct {
	Fid   string `json:"fid"`
	Name  string `json:"name"`
	IsDir bool   `json:"isDir"`
	Size  int64  `json:"size"`
	// Category 是夸克的分类, 1 = 视频.
	Category int `json:"category"`
	// UpdatedAt 毫秒时间戳.
	UpdatedAt int64 `json:"updatedAt"`
	// Thumbnail 夸克生成的缩略图 (视频是一帧截图), 没有时为空.
	Thumbnail string `json:"thumbnail,omitempty"`
	ParentFid string `json:"parentFid,omitempty"`
	// Duration 视频时长, 秒. 夸克没算出来时为 0.
	Duration int `json:"duration,omitempty"`
	Width    int `json:"width,omitempty"`
	Height   int `json:"height,omitempty"`
}

// RecentRecord 是「最近」里的一条记录: 一次转存、上传、播放等, 带涉及的文件.
type RecentRecord struct {
	// Source: SAVE_SHARE / UPLOAD_FILE / PLAY / VIEW_FILE ...
	Source string
	Time   int64
	Files  []File
}

// RecycledFile 是回收站里的一条: 被删掉的文件/目录, Time 是删除时间.
type RecycledFile struct {
	Fid       string
	Name      string
	IsDir     bool
	ParentFid string
	Time      int64
}

// Link 是取到的播放/下载地址, 请求时要带上 Headers.
type Link struct {
	URL     string
	Headers map[string]string
	Size    int64
}

// Error 是夸克接口返回的错误.
type Error struct{ Msg string }

func (e *Error) Error() string { return e.Msg }

// AuthError 表示登录态失效, 需要重新登录.
type AuthError struct{ Msg string }

func (e *AuthError) Error() string { return e.Msg }

// IsAuth 判断是不是登录失效.
func IsAuth(err error) bool {
	var a *AuthError
	return errors.As(err, &a)
}
