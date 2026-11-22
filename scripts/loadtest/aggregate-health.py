#!/usr/bin/env python3
"""헬스체크 결함 주입 회차(#216) 하나를 집계하고 판정한다. health-round.sh 가 회차 끝에 부른다.

설계: docs/tasks/health-check-progress/fault-scenarios.md 6절

읽는 것(회차 디렉터리):
  marks.tsv            회차 스크립트의 시각 표시(inject, unhealthy, new-healthy, recovered, end ...)
  tg.log               타깃 헬스 5초 폴링 — "<epoch>\t<id> <state> <reason> ;..."
  bg.csv               배경 k6 의 요청별 결과(--out csv). S1 에는 없다
  asg-activities.json  ASG 활동 기록 — 교체의 원인 문구
  cw-*.json            ALB CloudWatch(1분)
  liveness-t25.txt     주입 25초 뒤 관리 포트 liveness 응답(판정 분기 check)
  oom-journal.txt      S6 의 종료·재시작 기록

판정: 실제 결과(replace | restart | keep)를 정답·예측과 비교한다. 예측과 달라도 회차는 유효하다 — 예측이 틀렸다는 것이
결과다. 판정 불가(자료 누락)만 exit 3 으로 알린다.
"""
import argparse
import csv
import json
import os
import re
import sys
from datetime import datetime

WINDOW = 10  # 배경 트래픽 오류율을 보는 창(초)


def read_marks(path):
    marks = []
    with open(path, encoding='utf-8') as f:
        for line in f:
            epoch, name = line.rstrip('\n').split('\t', 1)
            marks.append((int(epoch), name))
    return marks


def first_mark(marks, prefix):
    for epoch, name in marks:
        if name == prefix or name.startswith(prefix + ' '):
            return epoch
    return None


def read_tg(path):
    """[(epoch, {id: (state, reason)})]"""
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, encoding='utf-8') as f:
        for line in f:
            if '\t' not in line:
                continue
            epoch, rest = line.rstrip('\n').split('\t', 1)
            targets = {}
            for part in rest.split(';'):
                fields = part.split()
                if len(fields) >= 2:
                    targets[fields[0]] = (fields[1], fields[2] if len(fields) > 2 else 'None')
            rows.append((int(epoch), targets))
    return rows


def read_background(path):
    """요청 하나당 (epoch, failed). k6 csv 의 http_req_failed 행을 쓴다(1 = 실패: 응답 없음·4xx·5xx)."""
    reqs = []
    if not os.path.exists(path):
        return reqs
    with open(path, encoding='utf-8', newline='') as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row.get('metric_name') == 'http_req_failed':
                reqs.append((int(float(row['timestamp'])), float(row['metric_value']) >= 1.0))
    return reqs


def background_summary(reqs, t0, end):
    if not reqs:
        return None
    base = [failed for ts, failed in reqs if t0 - 120 <= ts < t0]
    after = [(ts, failed) for ts, failed in reqs if t0 <= ts <= end]
    windows = {}
    for ts, failed in after:
        key = (ts - t0) // WINDOW
        total, bad = windows.get(key, (0, 0))
        windows[key] = (total + 1, bad + (1 if failed else 0))
    # 실패가 과반인 창 — "서비스가 안 되는" 구간. 첫 창부터 마지막 창까지를 장애 구간으로 본다.
    bad_windows = sorted(k for k, (total, bad) in windows.items() if total and bad / total > 0.5)
    outage = None
    if bad_windows:
        outage = {
            'from_sec': bad_windows[0] * WINDOW,
            'to_sec': (bad_windows[-1] + 1) * WINDOW,
            'duration_sec': (bad_windows[-1] + 1 - bad_windows[0]) * WINDOW,
        }
    return {
        'baseline_requests': len(base),
        'baseline_failure_rate': round(sum(base) / len(base), 4) if base else None,
        'after_inject_requests': len(after),
        'after_inject_failed': sum(1 for _, f in after if f),
        'outage': outage,
        'windows': [{'from_sec': k * WINDOW, 'requests': t, 'failed': b} for k, (t, b) in sorted(windows.items())],
    }


def termination_of(activities_path, instance_id, t0):
    if not os.path.exists(activities_path):
        return None
    with open(activities_path, encoding='utf-8') as f:
        data = json.load(f)
    for act in data.get('Activities', []):
        desc = act.get('Description', '')
        if 'Terminating EC2 instance' in desc and instance_id in desc:
            start = datetime.fromisoformat(act['StartTime'].replace('Z', '+00:00')).timestamp()
            if start >= t0 - 60:
                return {'epoch': int(start), 'cause': act.get('Cause', '')}
    return None


def cw_max(path, stat):
    if not os.path.exists(path):
        return None
    with open(path, encoding='utf-8') as f:
        points = json.load(f).get('Datapoints', [])
    return max((p[stat] for p in points), default=0)


def liveness_t25(path):
    if not os.path.exists(path):
        return None
    text = open(path, encoding='utf-8', errors='replace').read()
    code = re.search(r'http_code=(\d+)', text)
    check = re.search(r'"check"\s*:\s*"([a-z-]+)"', text)
    status = re.search(r'^\{"status"\s*:\s*"([A-Z_]+)"', text)
    return {
        'http_code': code.group(1) if code else None,
        'status': status.group(1) if status else None,
        'check': check.group(1) if check else None,
    }


def oom_restart(path):
    if not os.path.exists(path):
        return None
    text = open(path, encoding='utf-8', errors='replace').read()
    pids = re.search(r'pid_before=(\S+) pid_after=(\S+)', text)
    return {
        'oom_logged': 'OutOfMemoryError' in text,
        'process_exited': 'Main process exited' in text,
        'restart_scheduled': 'Scheduled restart' in text,
        'pid_changed': bool(pids and pids.group(1) != pids.group(2)),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir', required=True)
    ap.add_argument('--label', required=True)
    ap.add_argument('--config', required=True)
    ap.add_argument('--scenario', required=True)
    ap.add_argument('--truth', required=True, choices=['replace', 'restart', 'keep'])
    ap.add_argument('--predict', required=True, choices=['replace', 'restart', 'keep'])
    ap.add_argument('--instance-before', required=True)
    ap.add_argument('--replaced', required=True, choices=['yes', 'no'])
    args = ap.parse_args()

    d = args.dir
    marks = read_marks(os.path.join(d, 'marks.tsv'))
    t0 = first_mark(marks, 'inject')
    end = first_mark(marks, 'end')
    if t0 is None or end is None:
        print('판정 불가: marks.tsv 에 inject/end 가 없다')
        sys.exit(3)

    tg = read_tg(os.path.join(d, 'tg.log'))
    orig = args.instance_before
    first_unhealthy = next((ts for ts, t in tg if ts >= t0 and t.get(orig, ('',))[0] == 'unhealthy'), None)
    unhealthy_reason = next((t[orig][1] for ts, t in tg if ts >= t0 and t.get(orig, ('',))[0] == 'unhealthy'), None)
    new_healthy = next((ts for ts, t in tg if ts >= t0
                        and any(i != orig and s == 'healthy' for i, (s, _) in t.items())), None)
    termination = termination_of(os.path.join(d, 'asg-activities.json'), orig, t0)

    replaced = args.replaced == 'yes' or termination is not None
    oom = oom_restart(os.path.join(d, 'oom-journal.txt')) if args.scenario == 'S6' else None
    if replaced:
        actual = 'replace'
    elif oom and oom['pid_changed']:
        actual = 'restart'
    else:
        actual = 'keep'

    rel = lambda ts: None if ts is None else ts - t0
    result = {
        'label': args.label,
        'config': args.config,
        'scenario': args.scenario,
        'truth': args.truth,
        'predict': args.predict,
        'actual': actual,
        'correct': actual == args.truth,
        'matches_prediction': actual == args.predict,
        'inject_epoch': t0,
        'sec_to_first_unhealthy': rel(first_unhealthy),
        'unhealthy_reason': unhealthy_reason,
        'sec_to_termination': rel(termination['epoch']) if termination else None,
        'termination_cause': termination['cause'] if termination else None,
        'sec_to_new_healthy': rel(new_healthy),
        'liveness_t25': liveness_t25(os.path.join(d, 'liveness-t25.txt')),
        'oom': oom,
        'cw_unhealthy_host_max': cw_max(os.path.join(d, 'cw-UnHealthyHostCount.json'), 'Maximum'),
        'cw_elb_5xx_sum_max_per_min': cw_max(os.path.join(d, 'cw-HTTPCode_ELB_5XX_Count.json'), 'Sum'),
        'background': background_summary(read_background(os.path.join(d, 'bg.csv')), t0, end),
        'marks': [{'sec': e - t0, 'name': n} for e, n in marks],
    }
    with open(os.path.join(d, 'result.json'), 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    bg = result['background']
    outage = bg['outage'] if bg else None
    print(f"[{args.label}] 정답 {args.truth} / 예측 {args.predict} / 실제 {actual} — "
          f"{'정답' if result['correct'] else '오답'}, {'예측 일치' if result['matches_prediction'] else '예측 반증'}")
    print(f"  첫 unhealthy +{result['sec_to_first_unhealthy']}s({unhealthy_reason}), "
          f"종료 +{result['sec_to_termination']}s, 새 인스턴스 healthy +{result['sec_to_new_healthy']}s")
    print(f"  t0+25s liveness {result['liveness_t25']}, CloudWatch UnHealthyHostCount 최대 {result['cw_unhealthy_host_max']}")
    if bg:
        print(f"  배경: 기준선 실패율 {bg['baseline_failure_rate']}, 주입 뒤 실패 {bg['after_inject_failed']}/{bg['after_inject_requests']}, "
              f"장애 구간 {outage}")
    if oom:
        print(f"  OOM: {oom}")


if __name__ == '__main__':
    main()
