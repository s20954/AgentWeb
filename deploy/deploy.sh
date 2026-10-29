#!/bin/bash
# 民宿预约 Agent 一键部署脚本
# 用法：改掉 SERVER 地址，在项目根目录执行 bash deploy/deploy.sh
set -e

SERVER="user@your-server-ip"
FRONTEND_DIR="/var/www/minsu"
BACKEND_DIR="/opt/minsu"
JAR_NAME="minsu-backend.jar"

echo "== 1/5 构建前端 =="
npm run build

echo "== 2/5 构建后端 =="
mvn clean package -DskipTests

echo "== 3/5 上传前端 =="
scp -r dist/* "$SERVER:/tmp/minsu-dist/"
ssh "$SERVER" "sudo rm -rf ${FRONTEND_DIR}/* && sudo mv /tmp/minsu-dist/* ${FRONTEND_DIR}/ && sudo chown -R www-data:www-data ${FRONTEND_DIR}"

echo "== 4/5 上传后端 =="
scp "target/${JAR_NAME}" "$SERVER:${BACKEND_DIR}/"

echo "== 5/5 重启后端 =="
ssh "$SERVER" "sudo systemctl restart minsu-backend && sleep 2 && systemctl is-active minsu-backend"

echo ""
echo "部署完成，访问：http://${SERVER#*@}"
echo "看日志：ssh $SERVER 'sudo journalctl -u minsu-backend -f'"
