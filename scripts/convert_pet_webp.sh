#!/usr/bin/env bash
# 把鲸鱼娘桌宠 webm(VP9 alpha) 批量转成动态 WebP，供 Coil 加载。
set -u
A="D:/ai/cc Programm/arknightscode/deepseek-whale/assets/pet"
O="D:/ai/cc Programm/聊天终端安卓本地/app/src/main/assets/whale/pet"
FF="$HOME/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-7.0.2-full_build/bin/ffmpeg"

map=(
  "待机呼吸休闲:idle"
  "吃白饭:eat_rice"
  "吃Token:eat_token"
  "大口吃零食:eat_snack"
  "点击回应-开心跃动:touch_happy"
  "点击回应-害羞惊讶:touch_shy"
  "点击回应-傲娇生气:touch_angry"
  "点击回应-元气挥手:touch_wave"
  "点击回应-挠痒咯咯笑:touch_tickle"
  "玩游戏气急败坏:play_angry"
  "优雅女仆舞:play_dance"
  "鲸鱼吐泡泡特效:play_bubble"
  "原地小憩沉眠:sleep"
  "蓝鲸现世:levelup"
  "写代码:coding"
  "被吓一跳:surprised"
  "被鼠标拖拽悬空反馈:drag"
  "超大伸懒腰:stretch"
)

for pair in "${map[@]}"; do
  src="${pair%%:*}"
  dst="${pair##*:}"
  echo "=== $src -> $dst.webp"
  "$FF" -y -loglevel error -c:v libvpx-vp9 -i "$A/$src.webm" \
    -vf "scale=480:-1:flags=lanczos,fps=24" \
    -loop 0 -c:v libwebp_anim -lossless 0 -q:v 72 -an \
    "$O/$dst.webp" || echo "FAILED: $src"
done
echo "=== DONE ==="
ls -la "$O"
