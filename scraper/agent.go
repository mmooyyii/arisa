package main

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

//go:embed assets/guide.md
var guideText string

//go:embed assets/agent.md
var agentText string

// AgentResult: CostCny 是按 token 用量和 DeepSeek 价格算的花费.
type AgentResult struct {
	Ok      bool
	Summary string
	CostCny *float64
	LogFile string
}

// AgentRunner 用 DeepSeek 识别一批作品: 把 arisa-scraper 的几个子命令和看缩略图当成工具交给模型, 循环执行到它给出总结.
// 结论通过 decide / ask 命令写进映射表和待确认列表, 这里只负责跑工具、限时、留日志.
type AgentRunner struct {
	config AgentConfig
	home   string
	http   *http.Client
	api    string
}

func NewAgentRunner(c AgentConfig, home string) *AgentRunner {
	return &AgentRunner{c, home, httpClient(nil), "https://api.deepseek.com"}
}

const (
	agentMaxTurns  = 200
	agentMaxOutput = 20_000
)

func message(role, content string) map[string]any {
	return map[string]any{"role": role, "content": content}
}

func (a *AgentRunner) Run(works []PendingWork) AgentResult {
	workDir := filepath.Join(a.home, "agent")
	logDir := filepath.Join(a.home, "logs")
	_ = os.MkdirAll(workDir, 0o755)
	_ = os.MkdirAll(logDir, 0o755)
	logFile := filepath.Join(logDir, "agent-"+time.Now().Format("20060102-150405")+".log")
	var log strings.Builder
	prompt := fmt.Sprintf("待识别 %d 部:\n```json\n%s\n```\n", len(works), prettyJSON(works))
	log.WriteString(prompt + "\n")
	messages := []any{message("system", agentText+guideText), message("user", prompt)}
	deadline := time.Now().Add(time.Duration(a.config.TimeoutMinutes) * time.Minute)
	cost := 0.0
	finish := func(ok bool, summary string) AgentResult {
		status := "出错"
		if ok {
			status = "完成"
		}
		_ = os.WriteFile(logFile, []byte(fmt.Sprintf("%s\n--- 结果 (%s, ¥%.4f)\n%s\n", log.String(), status, cost, summary)), 0o644)
		c := cost
		return AgentResult{ok, summary, &c, logFile}
	}

	for turn := 0; turn < agentMaxTurns; turn++ {
		if time.Now().After(deadline) {
			return finish(false, fmt.Sprintf("超过 %d 分钟, 已停止", a.config.TimeoutMinutes))
		}
		resp, err := a.chat(messages)
		if err != nil {
			return finish(false, "调用 DeepSeek 出错: "+err.Error())
		}
		if resp.Usage != nil {
			cost += costCny(resp.Usage, time.Now())
		}
		if len(resp.Choices) == 0 {
			return finish(false, "DeepSeek 没有返回结果")
		}
		msg := resp.Choices[0].Message
		keep := map[string]any{"role": msg.Role, "content": msg.Content}
		if len(msg.ToolCalls) > 0 {
			keep["tool_calls"] = msg.ToolCalls
		}
		messages = append(messages, keep)
		text := ""
		if msg.Content != nil {
			text = strings.TrimSpace(*msg.Content)
		}
		if text != "" {
			log.WriteString("\n[AI] " + text + "\n")
		}
		if len(msg.ToolCalls) == 0 {
			return finish(true, text)
		}

		var images []string
		for _, call := range msg.ToolCalls {
			var args map[string]any
			_ = json.Unmarshal([]byte(call.Function.Arguments), &args)
			var output string
			switch call.Function.Name {
			case "arisa_scraper":
				var list []string
				if raw, ok := args["args"].([]any); ok {
					for _, x := range raw {
						list = append(list, fmt.Sprint(x))
					}
				}
				output = a.runScraper(list, workDir, false)
			case "thumbnail":
				fid, _ := args["fid"].(string)
				file := filepath.Join(workDir, "thumb-"+strings.ReplaceAll(fid, "/", "_")+".jpg")
				_ = os.Remove(file)
				out := a.runScraper([]string{"thumbnail", "--fid", fid, file}, workDir, true)
				if st, err := os.Stat(file); err == nil && st.Size() > 0 {
					images = append(images, file)
					output = "缩略图在下一条消息里"
				} else {
					output = out
				}
			default:
				output = "没有这个工具: " + call.Function.Name
			}
			argText, _ := json.Marshal(args)
			log.WriteString(fmt.Sprintf("\n[%s] %s\n%s\n", call.Function.Name, argText, output))
			messages = append(messages, map[string]any{"role": "tool", "tool_call_id": call.ID, "content": output})
		}
		// tool 消息只能放文字, 图片跟在后面单独发一条
		if len(images) > 0 {
			var content []any
			for _, f := range images {
				data, _ := os.ReadFile(f)
				content = append(content,
					map[string]any{"type": "text", "text": "缩略图 " + filepath.Base(f) + ":"},
					map[string]any{"type": "image_url", "image_url": map[string]any{"url": "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(data)}},
				)
			}
			messages = append(messages, map[string]any{"role": "user", "content": content})
		}
	}
	return finish(false, fmt.Sprintf("超过 %d 轮还没结束, 已停止", agentMaxTurns))
}

type toolCall struct {
	ID       string `json:"id"`
	Type     string `json:"type"`
	Function struct {
		Name      string `json:"name"`
		Arguments string `json:"arguments"`
	} `json:"function"`
}

type chatResponse struct {
	Choices []struct {
		Message struct {
			Role      string     `json:"role"`
			Content   *string    `json:"content"`
			ToolCalls []toolCall `json:"tool_calls"`
		} `json:"message"`
	} `json:"choices"`
	Usage map[string]any `json:"usage"`
}

func (a *AgentRunner) chat(messages []any) (*chatResponse, error) {
	body, _ := json.Marshal(map[string]any{"model": a.config.Model, "messages": messages, "tools": agentTools})
	req, _ := http.NewRequest("POST", a.api+"/chat/completions", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+a.config.APIKey)
	req.Header.Set("Content-Type", "application/json")
	resp, err := a.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	text, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, truncate(string(text), 300))
	}
	var r chatResponse
	return &r, json.Unmarshal(text, &r)
}

// runScraper 运行 arisa-scraper 子命令 (就是自己这个程序), 只放行识别用得到的几个.
func (a *AgentRunner) runScraper(args []string, workDir string, allowThumbnail bool) string {
	allowed := []string{"decide", "ask", "tmdb", "bangumi"}
	if allowThumbnail {
		allowed = append(allowed, "thumbnail")
	}
	if len(args) == 0 || !contains(allowed, args[0]) {
		return "只能用 " + strings.Join(allowed, " / ") + " 子命令"
	}
	exe, err := os.Executable()
	if err != nil {
		return "找不到 arisa-scraper: " + err.Error()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, exe, args...)
	cmd.Dir = workDir
	cmd.Env = append(os.Environ(), "ARISA_HOME="+a.home)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	err = cmd.Run()
	out := stdout.String()
	if err != nil {
		code := -1
		if cmd.ProcessState != nil {
			code = cmd.ProcessState.ExitCode()
		}
		e := strings.TrimSpace(stderr.String())
		if r := []rune(e); len(r) > 1000 {
			e = string(r[len(r)-1000:])
		}
		out = fmt.Sprintf("%s\n出错 (退出码 %d): %s", out, code, e)
	}
	out = strings.TrimSpace(out)
	if r := []rune(out); len(r) > agentMaxOutput {
		out = string(r[:agentMaxOutput]) + "\n…(太长, 截断了)"
	}
	return out
}

// costCny 按 DeepSeek Flash 的价格算. 余额接口只精确到分, 一小批的花费看不出来.
func costCny(usage map[string]any, now time.Time) float64 {
	n := func(k string) float64 {
		v, _ := usage[k].(float64)
		return v
	}
	cn := now.In(time.FixedZone("CST", 8*3600))
	// 工作日 9-12 点、14-18 点是高峰, 价格翻倍 (不算节假日, 节假日会多算)
	wd := cn.Weekday()
	h := cn.Hour()
	peak := wd != time.Saturday && wd != time.Sunday && (h >= 9 && h <= 11 || h >= 14 && h <= 17)
	yuan := n("prompt_cache_miss_tokens")*1.0 + n("prompt_cache_hit_tokens")*0.02 + n("completion_tokens")*4.0
	mult := 1.0
	if peak {
		mult = 2
	}
	return yuan / 1_000_000 * mult
}

var agentTools = []any{
	map[string]any{
		"type": "function",
		"function": map[string]any{
			"name":        "arisa_scraper",
			"description": "运行一条 arisa-scraper 命令, 只能用 decide / ask / tmdb / bangumi 子命令. 返回命令输出.",
			"parameters": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"args": map[string]any{
						"type":        "array",
						"items":       map[string]any{"type": "string"},
						"description": `arisa-scraper 后面的参数, 每个参数一项, 不用加引号. 如 ["tmdb", "search", "无职转生", "--tv"]`,
					},
				},
				"required": []string{"args"},
			},
		},
	},
	map[string]any{
		"type": "function",
		"function": map[string]any{
			"name":        "thumbnail",
			"description": "看一个视频文件的缩略图 (夸克生成的一帧画面), 图片会发给你.",
			"parameters": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"fid": map[string]any{"type": "string", "description": "视频文件的 fid, 一般用 sampleFid"},
				},
				"required": []string{"fid"},
			},
		},
	},
}
