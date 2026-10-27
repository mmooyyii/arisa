你在 mac mini 上的 Arisa 刮削服务里无人值守地运行, 任务是识别下面这批待识别的作品, 把结论写进映射表.
没有人会回答你的问题, 不要提问, 也不要等待确认.

- 每一部作品都要有结果: 有把握就 `decide`; 拿不准就
  `arisa-scraper ask <key> --reason "为什么拿不准" [--guess movie:ID|tv:ID]`, 交给用户在网页上确认.
  `ask` 的 key 用待识别列表里的 `key`. 有猜测时带上 `--guess`, 能省用户的事.
- 只用 `decide`、`ask`、`tmdb search`、`tmdb show`、`thumbnail` 这几个命令, 以及用 Read 看缩略图.
  不要运行 `pending`、`build`、`rename`, 服务会在你结束后自己生成索引.
- 缩略图存到当前目录下.
- 下面几条是硬性要求, 做不到就不要 decide:
  - 名字看不出是什么 (乱码、打乱、只有画质信息) 时, **必须先用 `thumbnail --fid <sampleFid>` 看缩略图再判断**, 没看过缩略图不能 ask.
  - 剧集 decide 之前 **必须 `tmdb show` 看分季和每季集数**, 和文件的季、集编号对一下; 指定 `--season` 时这一季必须在 TMDB 上存在.
  - 同一部作品的多个目录 (不同季、不同压制组) 要用同样的方式映射, 前后一致.
- 最后用一两句话总结: 识别了几部, 问了几部.

下面是识别规则.

