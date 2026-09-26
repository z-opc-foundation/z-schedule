#!/usr/bin/env bash
# P12 — p11 里 job 2/3 一行日志都没产生：那 24s 里本节点还不是 Leader（重启要等旧租约 30s 到期），
# start 走的是 "[Follower] skip registerJob"，任务其实没排上。
# 现在这个实例已经是 Leader，直接把 p11 建的现成任务再 start 一次，
# 才能真正验到 #15（执行结论落库）在真 MySQL 上到底修没修。
set -uo pipefail
cd ~/z-schedule-e2e
BASE="${BASE:-http://127.0.0.1:18086}"
q() { ./q.sh -N -B -e "$1"; }
hit() { curl -s -m 10 -H 'Content-Type: application/json' -X POST "$BASE$2" -d "${3:-}"; echo; }

BEFORE=$(q "SELECT COUNT(*) FROM z_schedule_job_log")
echo "leader 行: $(q "SELECT CONCAT_WS(' | ','owner',IFNULL(owner,'NULL'),'host',IFNULL(host,'NULL'),'expire',IFNULL(CAST(expire_time AS CHAR),'NULL')) FROM z_schedule_job_leader WHERE id=1")"
echo "日志基数: $BEFORE 行"

echo "start 2 (FIX_DELAY 4s) -> $(hit POST '/jobinfo/start?id=2')"
echo "start 3 (FIX_RATE 5s, retry=1) -> $(hit POST '/jobinfo/start?id=3')"
echo "--- 等 22s ---"; sleep 22

echo
echo "### 新增行按 job 汇总(job_id|行数|有结论|有msg|有结束时刻|已告警)"
q "SELECT CONCAT(job_id,' | ',COUNT(*),' | ',SUM(handle_code<>0),' | ',SUM(handle_msg IS NOT NULL),' | ',SUM(handle_time IS NOT NULL),' | ',SUM(alarm_status=1)) FROM z_schedule_job_log WHERE id>$BEFORE GROUP BY job_id ORDER BY job_id" | sed 's/^/  /'
echo "### 新增行结论分布(handle_code|行数)"
q "SELECT CONCAT(handle_code,' | ',COUNT(*)) FROM z_schedule_job_log WHERE id>$BEFORE GROUP BY handle_code ORDER BY handle_code" | sed 's/^/  /'
echo "### 新增行逐行(id|job|trigger_code|handle_code|handle_msg|触发时刻|结束时刻)"
q "SELECT CONCAT_WS(' | ',id,job_id,trigger_code,handle_code,IFNULL(handle_msg,'NULL-MSG'),DATE_FORMAT(trigger_time,'%H:%i:%s'),IFNULL(DATE_FORMAT(handle_time,'%H:%i:%s'),'NULL-TIME')) FROM z_schedule_job_log WHERE id>$BEFORE ORDER BY id" | sed 's/^/  /'
echo "### 调度指针"
q "SELECT CONCAT_WS(' ',id,trigger_type,CONCAT('status=',trigger_status),CONCAT('last=',trigger_last_time),CONCAT('next=',trigger_next_time)) FROM z_schedule_job_info WHERE id IN (2,3)" | sed 's/^/  /'

echo
echo "### 清理"
echo "stop 3 -> $(hit POST '/jobinfo/stop?id=3')"
echo "stop 2 -> $(hit POST '/jobinfo/stop?id=2')"
