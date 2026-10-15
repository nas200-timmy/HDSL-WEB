# docs/screenshots —— README 用的界面截图

README 里的五张图直接引用本目录（仓库内相对路径，由 GitHub 自己的 CDN 提供），
不再走任何图床——图床慢的时候整页都在等它，而仓库里的图跟着仓库走。

| 文件 | 拍的是什么 |
| --- | --- |
| `01-setup.webp` | 首启初始化（建管理员账号） |
| `02-main.webp` | 主界面 + 右下启动面板 |
| `03-instances.webp` | 实例列表页 |
| `04-download.webp` | 下载页 |
| `05-settings.webp` | 设置页 |

## 规格与重新生成

- 视口 **1440×900**（`02-main` 原来是 1920×1080，已统一缩到 1440 宽）。
- 转成 **WebP，`-quality 82`，`-strip`**：这批图从 ~3.9 MB 压到 ~270 KB（约 −93%）。
  截图里全是 UI 文字，82 已经看不出差别；要更清晰就提到 88~90，代价是体积翻倍。
- 重新生成（在面板跑起来、页面上有真实数据的状态下截图）：

```bash
magick shot.png -resize '1440x>' -strip -quality 82 docs/screenshots/03-instances.webp
```

> 换图前确认页面上没有不该公开的东西（内网域名、口令、真实账号名）；这是截图最容易漏的一步。
