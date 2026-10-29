#!/usr/bin/env bash
#
# 把本地仓库推送到 GitHub。
#
# 用法：
#   1) 先在 https://github.com/new 建一个空仓库（不要勾选 README/License，避免冲突）
#   2) 运行：
#        ./scripts/publish-to-github.sh git@github.com:<你的用户名>/code-agent.git
#      或
#        ./scripts/publish-to-github.sh https://github.com/<你的用户名>/code-agent.git
#
# 脚本会做三件事：检查工作区是否干净、补齐远程地址、推送 main 分支。
#
set -euo pipefail

REMOTE_URL="${1:-}"
if [ -z "$REMOTE_URL" ]; then
  echo "用法: $0 <git-remote-url>"
  echo "示例: $0 git@github.com:yourname/code-agent.git"
  exit 1
fi

if ! git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
  echo "当前目录不是 git 仓库"
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "工作区有未提交的改动，先提交再发布："
  git status --short
  exit 1
fi

if git remote get-url origin > /dev/null 2>&1; then
  echo "更新 origin: $REMOTE_URL"
  git remote set-url origin "$REMOTE_URL"
else
  echo "添加 origin: $REMOTE_URL"
  git remote add origin "$REMOTE_URL"
fi

echo "推送 main 分支…"
git push -u origin main

echo
echo "完成。仓库地址："
git remote get-url origin
