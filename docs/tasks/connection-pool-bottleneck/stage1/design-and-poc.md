# 1단계 설계 — Signed Cookie 기각과 Custom Policy 와일드카드 채택

> [TASK-PRESIGN-BOTTLENECK-FIX.md](../TASK-PRESIGN-BOTTLENECK-FIX.md)의 1단계("mycourse 이미지 접근을 Signed URL에서 Signed Cookie로 전환")를 실제로 착수하면서, **원안을 기각하고 다른 방식(Custom Policy + 와일드카드 `Resource`)을 채택한 근거**를 정리한 문서다. 두 방식은 "요청당 서명 1회"라는 목표가 같지만 클라이언트·인프라 비용이 전혀 다르다. 이 문서는 설계 결정과 PoC 검증까지 다루고, 실제 부하 측정 결과는 별도 문서([run-d-signature-once.md](run-d-signature-once.md))로 뺀다.

## 문제 재확인 — 0·3단계가 남긴 것

0단계(트랜잭션 분리)와 3단계(`AbortPolicy` 전환 + `CloudFrontSigningGate`)는 둘 다 **"서명이 일으키는 피해를 관리"**하는 접근이었다. [abortpolicy-gate-verification.md](../stage0/production/abortpolicy-gate-verification.md)의 결론대로 HikariCP 점유시간은 목표를 압도적으로 달성했지만(53.3ms→5.79ms), 두 가지가 남았다.

- **브라운아웃 21%** — 게이트가 49.4%에서 21.0%까지 줄였지만 0으로 만들지 못했다. 큐 사이징 재조정이 후속 과제로 남아 있었다.
- **CPU 99% 포화, 처리량 151 req/s 상한** — 서명 총량 자체는 줄지 않았으므로 당연한 결과다.

1단계는 "서명할 일 자체를 줄인다"는 다른 축의 개선이다.

## 원안(Signed Cookie) 기각 근거

### ① AWS가 쿠키를 권하는 이유는 CPU 절감이 아니다

FIX 문서 1단계는 [AWS 공식 문서](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-choosing-signed-urls-cookies.html)를 근거로 인용했다 — *"여러 개의 제한된 파일에 접근을 제공하려면 Signed Cookie를 쓰라."* 인용 자체는 정확하다. 그런데 원문을 확인하면 그 권고의 근거가 두 줄로 명시돼 있다.

> **Use signed cookies in the following cases:**
> - You want to provide access to multiple restricted files...
> - **You don't want to change your current URLs.**

즉 AWS가 제시하는 이점은 **"기존 URL을 안 바꿔도 된다"는 운영 편의**이지 서명 연산량 절감이 아니다. 문서 어디에도 "서명 CPU를 줄이려면 쿠키를 써라"는 서술은 없다. 쿠키로 서명이 1회가 되는 것은 부수효과이고, 같은 부수효과를 다른 방법으로도 얻을 수 있다면 AWS 문서는 그 방법을 배제하지 않는다.

### ② 클라이언트가 Android 네이티브라 "URL을 안 바꿔도 됨"이라는 이점이 성립하지 않는다

이 프로젝트의 FE는 Android 앱이다(README 기준). 쿠키 자동 전송은 브라우저의 동작이고, 네이티브 앱에서는 성립하지 않는다.

- **OkHttp의 기본 `CookieJar`는 `CookieJar.NO_COOKIES`(no-op)다** — 공식 Javadoc이 *"A cookie jar that never accepts any cookies"*로 정의한다. 별도 설정 없이는 쿠키를 저장하지도 보내지도 않는다.
- Coil은 OkHttp 기반이고 Glide도 OkHttp integration을 쓰는 경우가 많아, **둘 다 커스텀 `CookieJar`(또는 헤더 인터셉터)를 명시적으로 배선해야 CloudFront 쿠키 3종이 전송된다.**
- 그 외에도 쿠키 발급 API 호출, 만료 감지·재발급, 앱 재시작 시 영속화(SharedPreferences/DataStore)를 FE가 모두 구현해야 한다.

조사 범위에서 **Android + CloudFront Signed Cookie를 코드까지 공개한 실전 사례를 찾지 못했다.** 가장 근접한 자료(Medium, `[NodeJS, iOS, Android, React]`)는 실제 본문에 iOS 구현만 있고, 그 iOS 구현조차 쿠키 자동 전송을 포기하고 Kingfisher의 `AnyModifier`로 **요청 헤더에 수동으로 붙이는** 방식이었다. Apple 개발자 포럼에는 signed cookie가 AVPlayer 계열에서 동작하지 않는다는 보고도 있다.

즉 원안의 실질적 비용은 "백엔드 전환 + FE 쿠키 배선 + 앱 배포 사이클"이다.

### ③ 현재 인프라로는 쿠키를 심을 수조차 없다

`.env`의 `CLOUDFRONT_DOMAIN`이 **CloudFront 기본 도메인(`<배포ID>.cloudfront.net`)**이고 API는 `yourtrip.site`다.

- 앱 서버(`yourtrip.site`)가 `Set-Cookie`로 `cloudfront.net` 도메인 쿠키를 심는 것은 쿠키 스펙상 불가능하다.
- 게다가 `cloudfront.net`은 **Public Suffix List 등재 도메인**이라 그 레벨의 쿠키는 아예 거부된다.

따라서 Signed Cookie를 쓰려면 **CloudFront 커스텀 도메인(`cdn.yourtrip.site`) + ACM 인증서 + Route53 레코드 + 기존 이미지 URL 도메인 마이그레이션**이 선행돼야 한다. FIX 문서 1단계에는 이 항목이 없었다.

### ④ AWS가 제시한 완화책이 모바일에서 무력화된다

[AWS signed-cookies 문서](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-cookies.html)는 안전한 사용을 위해 세 가지를 권고하는데, 앞의 둘이 이 환경에서 안 먹힌다.

| AWS 권고 | 이 환경에서의 문제 |
|---|---|
| `Expires`/`Max-Age`를 빼 **세션 쿠키**로 만들어 브라우저 종료 시 삭제 | 네이티브 앱에는 "브라우저 종료" 개념이 없다 |
| custom policy로 **viewer IP 제한** | 모바일은 셀룰러↔WiFi 전환으로 IP가 계속 바뀌어 오탐이 난다 |
| `Domain`을 최대한 좁게 | 위 ③ 때문에 애초에 도메인 설정이 불가능하다 |

### ⑤ 취소 불가 범위가 URL보다 넓다

CloudFront는 요청 시점에 쿠키의 만료시각만 검사한다. 만료 전 무효화 수단은 문서에 없고, 실질적 revoke는 **key group에서 공개키를 제거 = 그 키로 서명된 모든 URL/쿠키를 동시에 무효화**뿐이다. 지금의 Signed URL도 TTL 60분이라 같은 노출 창을 갖지만, 쿠키는 URL과 달리 **`Path` 범위 전체에 대한 접근권**이라 유출 시 피해 범위가 더 넓다.

---

## 채택안 — Custom Policy의 와일드카드 `Resource`

### 동작 원리

핵심은 **CloudFront가 파일에 서명하는 게 아니라, "누가 무엇을 언제까지 볼 수 있는지" 적은 정책 JSON에 서명한다**는 점이다.

```json
// Canned Policy (기존) — 파일 하나를 정확히 지목
{"Statement":[{"Resource":"https://{domain}/private/2026-08-11/3f9a....jpg",
               "Condition":{"DateLessThan":{"AWS:EpochTime":1755000000}}}]}

// Custom Policy (채택) — 코스 폴더를 규칙으로 지정
{"Statement":[{"Resource":"https://{domain}/private/42/*",
               "Condition":{"DateLessThan":{"AWS:EpochTime":1755000000}}}]}
```

두 번째도 **문서 한 장**이다. 문서가 하나니 서명 연산도 1회다.

CloudFront 엣지는 요청을 받으면 다음 순서로 검사한다.

1. `Policy`를 base64 디코드
2. `Signature`가 그 Policy + 등록된 공개키로 검증되는가
3. `Condition`의 만료시각이 아직 안 지났는가
4. **요청 URL이 `Resource` 패턴에 매칭되는가**

**2번(서명 진위)과 4번(경로 매칭)이 분리돼 있다**는 것이 이 방식의 근거다. 같은 `Policy`+`Signature` 쌍을 유지한 채 4번에 들어가는 파일명만 바꿔도 매번 통과한다. AWS 문서도 custom policy의 `Resource`가 `*`/`?` 와일드카드를 지원하며 *"하나의 정책으로 여러 파일에 대한 signed URL들을 만들 수 있다"*고 명시한다.

따라서 응답 조립은 이렇게 된다.

```
서명 1회  →  Policy=xxx & Signature=yyy & Key-Pair-Id=zzz
             ↓ 이 쿼리스트링을 그대로 재사용 (문자열 결합만)
이미지 1:  https://{domain}/private/42/a.jpg?Policy=xxx&Signature=yyy&Key-Pair-Id=zzz
이미지 2:  https://{domain}/private/42/b.jpg?Policy=xxx&Signature=yyy&Key-Pair-Id=zzz
```

### 원안 대비 이점

| | Signed Cookie | Custom Policy 와일드카드 |
|---|---|---|
| 서명 횟수 | 요청당 1회 | **요청당 1회 (동일)** |
| Android FE 변경 | CookieJar 배선·재발급·영속화 전부 | **없음** (응답 스키마 그대로) |
| 인프라 변경 | 커스텀 도메인 + ACM + Route53 | **없음** (아래 참조) |
| 유출 시 노출 범위 | `Path` 범위 전체 | 코스 1개 |
| 취소 설계 | 별도 설계 필요 | 현행 TTL 정책 그대로 |

`terraform` 변경이 불필요한 이유는 두 가지다. [cloudfront.tf](../../../../terraform/cloudfront.tf)의 `ordered_cache_behavior { path_pattern = "private/*" }`가 `private/{courseId}/...`를 그대로 매칭하고(서명 검증은 behavior 단위라 canned→custom 전환과 무관), 캐시 정책이 `Managed-CachingOptimized`라 쿼리스트링을 캐시 키에서 제외하므로 엣지 캐시 재사용도 그대로다.

### 보안 범위 — 실질 증분이 작은 이유

와일드카드는 "URL 하나가 유출되면 그 패턴 전체가 열린다"는 트레이드오프를 가진다. 다만 이 시스템에서 그 증분은 작다.

```
정책 Resource: private/42/*

/private/42/a.jpg  → 매칭 → 통과
/private/42/b.jpg  → 매칭 → 통과
/private/99/a.jpg  → 매칭 안 됨 → 403   ← 다른 코스는 여전히 차단
```

- **현행**: URL 1개 유출 → 이미지 1장 노출(60분)
- **코스 와일드카드**: URL 1개 유출 → 그 코스의 이미지 전체 노출(60분)

그런데 상세조회 응답 하나가 이미 "그 코스, 그 day의 모든 이미지 URL"을 담고 있다. 와일드카드로 실제 넓어지는 건 **"같은 코스의 다른 day 이미지"**뿐이고, 그건 동일 소유자의 자산이며 같은 API로 접근 가능하다. TTL 정책도 60분 그대로 유지한다.

### 부수효과 — 3단계 인프라의 존재 이유가 소멸한다

요청당 서명이 1회면 fan-out 구조 자체가 사라진다. `cloudFrontSigningExecutor`(병렬 서명), `CloudFrontSigningGate`(요청 단위 세마포어), 큐 사이징, 부분 응답(브라운아웃)이 전부 **fan-out을 전제로 만들어진 장치**였다.

이건 3단계 작업이 잘못됐다는 뜻이 아니다. 그때는 요청 1건이 이미지 수만큼 태스크를 만드는 구조였고, 그 구조에서는 격리와 배압이 필요했다. **전제가 바뀌어서 장치가 불필요해진 것**이며, 이 시간축 자체를 [run-e-infra-removed.md](run-e-infra-removed.md)에 기록한다.

다만 제거 시점은 측정 이후로 미룬다 — "서명 1회 전환"과 "인프라 제거"의 효과를 분리 측정하기 위해서다(Run D → Run E).

---

## 기대효과의 냉정한 추정

착수 전에 [abortpolicy-gate-verification.md](../stage0/production/abortpolicy-gate-verification.md)의 Run A/B 수치로 **서명이 실제로 쓰는 CPU 지분**을 역산했다.

**Run A (AbortPolicy 단독)**

| 항목 | 값 |
|---|---|
| 제출된 서명 태스크 | 45,855 요청 × 10장 = 458,550건 |
| executor 거부(= CPU 안 씀) | 203,779건 |
| 실제 서명 실행 | 254,771건 ÷ 290초 = **878 signs/s** |
| CPU 소비 | 878 × 368us = **0.323 vCPU** |
| t3.small(2 vCPU) 대비 | **16.2%** |

**Run B (게이트 활성)**도 같은 방식으로 853 signs/s → **15.7%**로, 두 arm이 거의 일치한다.

여기서 중요한 함의가 나온다. 서명 스레드 2개의 이론상 처리량은 `2 ÷ 368us = 5,435 signs/s`인데 실측은 878이다. **서명 풀이 자기 용량에 막힌 게 아니라, 2 vCPU를 Tomcat 스레드들과 나눠 쓰면서 16%만 얻은 것**이다.

> 즉 CPU 99% 포화의 주범은 서명이 아니다. 서명은 16%짜리 조연이고, 나머지 84%는 다른 데서 나간다.

따라서 서명을 1/10로 줄였을 때 처리량 개선 상한은 `151 ÷ (1 − 0.16) ≈ 180 req/s`, **약 +19%**다. 판정 3이 "경합 하 실효 서명비용이 벤치마크(368us)보다 높았다"고 결론냈으므로 16%는 하한이고, 실효비용이 2배라면 32%(개선폭 +47%)까지 올라간다.

**이 폭은 "근본적 개선"이라 부르기엔 좁다.** 그럼에도 이 방향을 채택하는 근거는 처리량이 아니라 다음 두 가지다.

1. **브라운아웃 21%가 구조적으로 0이 된다** — 게이트가 못 한 것을 서명 구조 자체가 해결한다.
2. **복잡한 방어 장치를 통째로 걷어낼 수 있다** — 유지보수 대상이 사라지는 것은 처리량 수치로 환산되지 않는 가치다.

실제 서명 CPU 비중은 Run D의 JFR에서 `cloudfront-signing-*` 스레드의 전체 샘플 대비 비율로 직접 확인한다([parse-execution-samples.mjs](../../../../scripts/jfr/parse-execution-samples.mjs)가 이미 스레드 접두사별 분포를 출력한다).

### 마이크로벤치마크 — 전환 비용은 없고 요청당 비용은 10.6배 줄었다

착수 전 우려 하나는 "custom policy는 정책 JSON이 길어져 SHA1 입력이 커지므로 서명 1회가 더 비쌀 수 있다"는 것이었다. `SigningBenchmarkTest`에 두 방식을 같은 실행에서 비교하는 케이스를 추가해 확인했다(dev 머신, 12코어, 워밍업 2,000회 + 측정 10,000회).

| 항목 | 값 |
|---|---|
| canned policy 서명 1회 | 257.32 us/op |
| custom policy 서명 1회 | **243.63 us/op** (canned 대비 −5.3%) |
| 요청당 서명 비용(이미지 10장 기준) | 2,573.17 us → **243.63 us** (**10.6배 감소**) |
| 서명 없는 public URL 조립(문자열 결합) | 0.31 us/op |

**우려는 근거가 없었다.** custom이 오히려 소폭 낮게 나왔는데 5% 차이는 측정 노이즈 범위로 보는 게 타당하고, 요점은 "정책 JSON 길이가 ECDSA 서명 비용에 유의미한 영향을 주지 않는다"는 것이다. 서명 비용은 해시 입력 길이가 아니라 타원곡선 연산이 지배한다.

마지막 줄(0.31us)도 중요하다 — 이번 전환에서 **이미지 N장에 쿼리스트링을 붙이는 작업은 서명 1회의 1/800 수준**이라 사실상 공짜다. "서명 1회 + 문자열 결합 N회"가 "서명 N회"를 대체할 수 있다는 설계의 전제가 실측으로 확인된 셈이다.

> 이 값들은 dev 머신 기준이라 EC2(t3.small)의 절대값과 다르다(EC2 실측 canned 368us). 여기서 의미 있는 건 절대값이 아니라 **같은 머신·같은 실행에서의 상대 비교**다.

---

## 결정된 설계

| 항목 | 결정 | 근거 |
|---|---|---|
| 서명 범위 | **코스 단위** — key `private/{courseId}/{uuid}.ext`, Resource `https://{domain}/private/{courseId}/*` | 노출 범위가 응답 범위와 정확히 일치. 코스 삭제 시 prefix 단위 invalidation도 가능해진다 |
| 기존 비공개 데이터 | **폐기**(하위호환 분기 없음), **백업 없이** | 두 경로가 공존하면 복잡도가 오르고 부하테스트 결과 해석도 흐려진다 |
| 인프라 제거 시점 | **2단계 분리**(Run D → 제거 → Run E) | "서명 횟수 감소"와 "인프라 제거"의 효과를 분리 측정 |
| 실패 시맨틱 | **fail-closed**(503) | 서명이 1개가 되면 fail-open은 "이미지 0장짜리 200"이 되어 브라운아웃이 최악 형태로 악화된다 |
| 서명 TTL | **60분 유지** | 변경 변수를 "서명 횟수" 하나로 통제. [application.yml](../../../../src/main/resources/application.yml)의 기존 근거(짧은 TTL은 느린 네트워크에서 이미지가 깨진다)도 그대로 유효 |

### key 구조를 바꿔야 하는 이유

현재 key는 [S3Service.java](../../../../src/main/java/backend/yourtrip/global/s3/service/S3Service.java)에서 `private/{yyyy-MM-dd}/{UUID}.{ext}`로 만들어진다. **소유자·코스 정보가 key에 전혀 없다.** 와일드카드로 좁힐 수 있는 최소 단위가 `private/2026-08-11/*`인데 이건 "그날 업로드된 전체 사용자의 모든 이미지"라 쓸 수 없다.

CloudFront 정책은 Statement를 하나만 허용해 임의의 UUID 10개를 나열하는 것도 불가능하다. **와일드카드가 유일한 경로이고, 따라서 key 구조 재설계가 필수 선행 작업이다.**

---

## PoC 검증

> **결과: 6개 판정 전부 통과.** 코드 변경을 진행해도 좋다는 결론이다. 아래에 실측값과, 검증 과정에서 발견한 두 가지(캐시 키 동작의 더 강한 증거 / 폐기 절차를 수정해야 하는 IAM 권한 제약)를 함께 남긴다.

### 검증할 명제

> custom policy를 `Resource: https://{domain}/private/{courseId}/*`로 서명한 쿼리스트링을, **서명할 때 언급하지 않은 다른 경로**에 붙여도 CloudFront가 200을 준다.

여기서 막히면 계획 전체가 무산되므로 **코드를 한 줄도 고치기 전에** 실배포 distribution에서 확인한다.

### 방법

앱 빌드 없이 openssl로 재현한다. AWS SDK 소스(`cloudfront-2.51.0-sources.jar`)를 열어 확인한 결과, SDK는 compact JSON 정책을 `SHA1withECDSA`(EC 키인 경우)로 서명하고 base64 후 `+`→`-`, `=`→`_`, `/`→`~`로 치환한다. `openssl dgst -sha1 -sign` + `tr '+=/' '-_~'`가 정확히 같은 결과를 낸다.

테스트 오브젝트를 `private/999999/poc-a.jpg`, `private/999999/poc-b.jpg`, `private/999998/poc-c.jpg`에 올리고, `private/999999/*` 정책으로 서명한 쿼리스트링 하나로 아래를 확인한다.

### 판정 매트릭스 — 실측 결과

정책 Resource `https://{domain}/private/999999/*`, 만료 1시간, 쿼리스트링 길이 **332자**.

| # | 요청 | 기대 | 실측 | 판정 | 의미 |
|---|---|---|---|---|---|
| 1 | `poc-a.jpg?{QS}` | 200 | **200** | PASS | 와일드카드 정책 자체가 유효 |
| 2 | `poc-b.jpg?{QS}` | **200** | **200** | **PASS** | ★ 핵심 — 서명 때 언급 안 한 경로에 재사용 성공 |
| 3 | `poc-c.jpg?{QS}` (다른 코스) | **403** | **403** | **PASS** | 코스 경계가 실제로 잘린다 |
| 4 | `poc-b.jpg` (서명 없음) | 403 | **403** | PASS | `private/*` behavior에 여전히 걸린다 |
| 5 | 만료시각을 과거로 둔 정책 | 403 | **403** | PASS | 만료 조건이 실제로 평가된다 |
| 6 | 캐시 키에서 쿼리스트링 제외 | `Hit` | **`Hit`** | PASS | 아래 "캐시 키 동작" 참고 |

**2번과 3번이 이 PoC의 본체다.** 2번은 "와일드카드 재사용이 동작한다"를, 3번은 "그럼에도 코스 경계는 진짜 경계다"를 각각 증명한다. 2번만 확인하고 넘어갔다면 스코프가 실제로 좁혀졌는지 모른 채 진행하게 됐을 것이다.

4번은 "경로 세그먼트가 깊어지면서 `private/*` behavior에 안 걸리고 default behavior(무서명 공개)로 새는" 시나리오를 배제한다 — 200이 나왔다면 심각한 신호였다.

### 캐시 키 동작 — 원래 판정보다 강한 검증으로 대체

첫 실행에서 6번이 `Miss from cloudfront`로 나왔다. 원인을 규명하기 위해 검증을 세 갈래로 확장했고, 그 결과 **원래 세우려던 판정보다 훨씬 강한 증거를 얻었다.**

| 검증 | 결과 |
|---|---|
| **A. 완전히 동일한 URL 4회 반복** | 1회차 `Miss`(age=0) → 2·3회차 `Hit`(age=1) → 4회차 `Hit`(age=2). 전부 같은 POP(ICN57-P4) |
| **B. 서명만 다른 URL 3회** (만료를 1초씩 어긋내 `Policy`/`Signature`가 매번 달라짐) | **3회 모두 `Hit`** (age=2→3→4) |
| **C. canned policy로 같은 객체 요청** | **`Hit`** (age=5) |

**B가 결정적이다.** 서명이 매 요청 달라지는데도 엣지가 같은 캐시 엔트리를 반환했다 — `Policy`/`Signature`/`Key-Pair-Id`가 캐시 키에 **포함되지 않는다**는 직접 증거다. C는 여기에 더해 **canned policy와 custom policy가 같은 캐시 엔트리를 공유**함을 보여준다. 즉 이번 전환이 기존에 쌓인 엣지 캐시를 무효화하지 않는다.

첫 실행의 `Miss`는 캐시 정책 문제가 아니라 단순히 그 객체가 아직 그 POP에 워밍되지 않은 상태였던 것이다(A의 1회차와 동일한 현상). **원래 판정 기준("2회차에 Hit")은 첫 요청이 언제 캐시에 반영되는지에 의존해 불안정했고, B/C 방식이 명제를 직접 검증한다** — 판정 기준을 사후에 더 나은 것으로 교체한 사례로 남긴다.

### 부수 발견 — 앱 IAM 유저에 `s3:ListBucket` 권한이 없다

정리 단계에서 `aws s3 rm s3://{bucket}/private/999999/ --recursive`가 실패했다.

```
AccessDenied ... user/service-accounts/yourtrip-s3-app-user is not authorized to
perform: s3:ListBucket on resource: "arn:aws:s3:::yourtrip-media-520426835144"
```

앱 유저는 `GetObject`/`PutObject`/`DeleteObject`만 갖고 있고 버킷 나열 권한이 없다. 최소 권한 원칙상 올바른 설계지만, **기존 데이터 폐기 절차(A5)가 `--recursive` 삭제를 전제하고 있어 그대로는 실행할 수 없다.**

대응: **DB에서 key 목록을 뽑아 `delete-object`로 개별 삭제**한다. 어차피 `place_image` 행을 지우기 전에 key 목록을 확보해야 하므로 절차가 자연스럽게 맞물리고, "DB가 알고 있는 것만 지운다"는 점에서 오히려 안전하다(버킷 전체를 훑다가 예상 못 한 오브젝트를 지울 위험이 없다). PoC 정리도 이 방식(`aws s3api delete-object` 3회 + `head-object`로 삭제 확인)으로 끝냈다.

이 제약은 폐기 스크립트(`scripts/sql/purge-legacy-private-images.sql`)를 작성할 때 반영한다.

### PoC-2 (앱 배포 후 재확인)

openssl PoC가 통과해도 "앱이 만든 쿼리스트링"이 동일한지는 별개 문제다. 서명 로직 전환 배포 후 실제 상세조회 응답의 URL을 그대로 `curl`해 200을 확인하고, 그 쿼리스트링을 응답 내 **다른 이미지 URL**에 붙여 200이 나오는지, **다른 코스의 URL**에 붙이면 403이 나오는지 한 번 더 본다. 이 검증은 **기존 데이터 폐기(비가역)와 Run D 측정의 선행 조건**이다.

---

## 채택하지 않은 대안

| 항목 | 이유 |
|---|---|
| **Signed Cookie** | 위 "원안 기각 근거" 5개 참조 |
| **서명 URL을 Redis에 TTL 캐싱** | 실무 근거가 있는 패턴이다([Ben Nadel 케이스 스터디](https://www.bennadel.com/blog/3685-performance-case-study-caching-cryptographically-signed-urls-in-redis-in-lucee-5-2-9-40.htm) — HMAC-SHA1에서도 p95를 1/3로 단축). 다만 ① 와일드카드 전환 후에는 서명 호출이 이미 1/10이라 한계 이득이 작고, ② ElastiCache 왕복(0.3~1ms)이 서명 1회(368us)보다 비쌀 수 있으며, ③ **실서비스 접근 패턴에서 히트율이 부하테스트보다 훨씬 낮다**(pool 모드는 3,000 코스를 무작위 조회해 TTL 60분이면 히트율 93%가 나오지만, 실제로는 본인의 비공개 코스를 시간·일 단위 간격으로 재방문한다). 측정 후 재검토 대상으로 남긴다 |
| **엣지에서 인증(CloudFront Functions + HS256 JWT)** | HMAC은 ECDSA보다 수십~수백 배 싸고 비용도 $0.10/100만 호출로 저렴하다. 다만 KeyValueStore·함수 배포·키 로테이션 등 인프라 구성요소가 늘고, **대칭키가 엣지에 상주**해 공개키 서명 대비 키 노출 범위가 커진다. 현재 규모에서는 비례가 맞지 않는다 |
| **인가 후 302 리다이렉트(OAC + 앱 레벨 인가)** | 이미지 1장당 앱 서버를 1회 더 태우므로 "요청 1건에 이미지 수십 장" 구조에서는 앱 요청 수가 수십 배로 늘어난다. 서명 CPU 병목을 스레드/네트워크 병목으로 바꾸는 셈. 게다가 302가 엣지에 캐시되면 남의 서명 URL이 서빙될 위험이 있다 |
| **key에 충분한 엔트로피를 두고 공개 전환(capability URL)** | 서명 비용이 0이 되지만 "비공개"라는 제품 약속을 깬다 |

## 참고 문서

- [TASK-PRESIGN-BOTTLENECK-FIX.md](../TASK-PRESIGN-BOTTLENECK-FIX.md) — 전체 계획(이 문서가 1단계를 대체한다)
- [stage0/production/abortpolicy-gate-verification.md](../stage0/production/abortpolicy-gate-verification.md) — 3단계 결과. 이 문서의 CPU 예산 역산이 그 실측치에 기반한다
- [run-d-signature-once.md](run-d-signature-once.md) — 서명 1회 전환의 부하 측정 결과(Run D/D2)
- [run-e-infra-removed.md](run-e-infra-removed.md) — 게이트·executor 제거 후 재측정(Run E/F)
