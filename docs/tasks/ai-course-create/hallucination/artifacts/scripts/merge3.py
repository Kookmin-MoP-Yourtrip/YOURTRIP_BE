import csv

# 배치 우선순위: 최신 배치가 그 requestId의 canonical source.
# (batch3, batch2, batch1) 순 — 나중 배치일수록 하네스가 더 개선된 상태에서 측정됨.
BATCHES = [
    ("batch3", "results/hallucination-baseline-20260812-184600"),
    ("batch2", "results/hallucination-baseline-20260812-153453"),
    ("batch1", "results/hallucination-baseline-20260811-174326"),
]

def read_csv(path, encoding="utf-8-sig"):
    with open(path, newline="", encoding=encoding) as f:
        return list(csv.DictReader(f))

req_by_batch = {}
for name, prefix in BATCHES:
    req_by_batch[name] = {row["requestId"]: row for row in read_csv(prefix + "-requests.csv")}

places_by_batch = {}
for name, prefix in BATCHES:
    rows = read_csv(prefix + ".csv")
    by_req = {}
    for row in rows:
        by_req.setdefault(row["requestId"], []).append(row)
    places_by_batch[name] = by_req

# requestId 1~30 각각에 대해 canonical batch(가장 우선순위 높은, 기록이 존재하는 배치) 결정
canonical = {}
for rid in [str(i) for i in range(1, 31)]:
    for name, _ in BATCHES:
        if rid in req_by_batch[name]:
            canonical[rid] = name
            break

# 최종 requests.csv 구성
final_requests = []
source_counts = {"batch1": 0, "batch2": 0, "batch3": 0, "missing": 0}
for rid in [str(i) for i in range(1, 31)]:
    if rid not in canonical:
        source_counts["missing"] += 1
        print(f"경고: requestId {rid} 는 어떤 배치에도 기록이 없음")
        continue
    b = canonical[rid]
    source_counts[b] += 1
    row = dict(req_by_batch[b][rid])
    row["sourceBatch"] = b
    final_requests.append(row)

# 최종 places.csv 구성 — canonical batch의 place 행만 채택
final_places = []
for rid in [str(i) for i in range(1, 31)]:
    if rid not in canonical:
        continue
    b = canonical[rid]
    for row in places_by_batch[b].get(rid, []):
        row2 = dict(row)
        row2["sourceBatch"] = b
        final_places.append(row2)

print(f"\ncanonical 배치별 요청 수: {source_counts}")
print(f"최종 요청 레코드: {len(final_requests)}건")
print(f"최종 장소 레코드: {len(final_places)}건")

# 파일로 저장
req_fields = list(final_requests[0].keys())
with open("results/merged3-requests.csv", "w", newline="", encoding="utf-8-sig") as f:
    w = csv.DictWriter(f, fieldnames=req_fields)
    w.writeheader()
    w.writerows(final_requests)

place_fields = list(final_places[0].keys())
with open("results/merged3-places.csv", "w", newline="", encoding="utf-8-sig") as f:
    w = csv.DictWriter(f, fieldnames=place_fields)
    w.writeheader()
    w.writerows(final_places)

print("\n저장 완료: results/merged3-requests.csv, results/merged3-places.csv")

# 간단 검증: 중복 requestId가 places에 없는지 확인
from collections import Counter
req_counts_in_places = Counter(r["requestId"] for r in final_places)
dup_reqs = [rid for rid, cnt in req_counts_in_places.items()
            if len(set(r["sourceBatch"] for r in final_places if r["requestId"] == rid)) > 1]
print(f"places.csv에서 서로 다른 배치가 섞인 requestId (있으면 버그): {dup_reqs}")
