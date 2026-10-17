# noteu 오류를 BDD 분석기로 보내기

HTTP MVC 요청에서 발생한 서버 오류를 수집해 분석기의 `POST /events`로 비동기 전송한다. 기존 컨트롤러와 예외 처리기가 만드는 HTTP 상태·본문은 유지한다. 이 기능은 기본값이 꺼져 있으며, 아래 Compose 오버레이로 활성화한다.

## 처리 흐름과 파일 역할

```text
HTTP 요청 → ErrorReportingInterceptor: 요청 식별자와 경로 기록
         → 기존 컨트롤러
         → 예외 발생 시 ErrorCaptureResolver: 예외만 기록하고 null 반환
         → 기존 Spring 예외 처리와 오류 응답
         → ErrorReportingInterceptor: 오류 이벤트를 메모리 큐에 넣음
         → ErrorEventReporter: 별도 스레드에서 분석기 POST /events
         → 분석기: 오류 그룹화 → 같은 분석 기준의 기존 작업 재사용
```

- `ErrorReportingConfig`: 활성화 설정을 읽고 인터셉터와 관찰용 예외 처리기를 등록한다.
- `ErrorReportingProperties`: 분석기 주소, 서비스 이름, 배포 SHA, 큐 크기, 시간 제한을 보관한다.
- `ErrorCaptureResolver`: 기존 처리기보다 먼저 예외 객체를 요청 속성에 보관한다. `null`을 반환하므로 다음 예외 처리기가 기존 방식으로 응답을 결정한다.
- `ErrorReportingInterceptor`: 요청이 끝날 때 상태와 예외를 모아 이벤트를 만든다. 요청 속성으로 같은 요청의 중복 제출을 막는다.
- `ErrorEvent`: 분석기가 받는 JSON 모양을 고정한 불변 데이터다. 요청 객체를 비동기 스레드에 넘기지 않는다.
- `ErrorEventReporter`: 크기가 제한된 큐와 전용 스레드 하나를 사용한다. 분석기 지연·실패·큐 초과가 원래 요청을 실패시키지 않도록 처리한다.

기본 큐 크기는 64개, 연결 제한은 2초, 요청 제한은 5초다. 전송 실패는 제한된 빈도로 서버 로그에 남긴다. 이 초기 전송기는 재시도나 디스크 보관을 하지 않으므로, 큐가 가득 차거나 앱이 종료되면 아직 보내지 못한 이벤트는 사라질 수 있다.

## 수집 범위와 민감 정보

- MVC 컨트롤러에 도달한 서버 오류를 대상으로 한다. 정상 응답과 기존 처리기가 정한 4xx 응답은 수집하지 않는다.
- Spring Security 필터에서 끝난 요청, WebSocket/STOMP 메시지, 스케줄 작업, 코드 내부에서 이미 삼킨 예외는 이 MVC 수집 경로의 대상이 아니다.
- 요청 본문·쿼리·헤더·쿠키·사용자 세션은 전송하지 않는다. 요청 경로와 라우트 템플릿은 전달하며, 그룹화에는 라우트 템플릿을 사용한다. 경로의 세미콜론 파라미터는 제거하고, 원본 예외 메시지 대신 예외 종류와 제한된 스택 정보를 보낸다.
- 수집 기능은 오류 응답을 고치지 않는다. 예를 들어 현재 비로그인 채팅방 목록 요청의 500 오류도 기존 동작 그대로 남고, 분석할 근거만 전달한다.

## 1. 분석기에서 noteu 근거를 등록하기

현재 제공한 연결 대상은 다음 하나다.

```text
service: noteu
method: GET
route_template: /subjects/{subject-id}/chats/rooms
```

`integration/analyzer/evidence/noteu/chat-rooms/`가 근거의 원본이다. `integration/analyzer/compose.noteu.yml`은 이 폴더를 분석기 API의 `/app/evidence/noteu`에 읽기 전용으로 마운트한다. 상대 경로는 첫 번째 Compose 파일인 분석기 `compose.yaml`의 디렉터리를 기준으로 해석되며 두 프로젝트가 같은 상위 폴더에 있어야 한다. 다른 위치에서는 해당 오버레이의 bind source를 실제 noteu 경로로 지정한다. 호스트 폴더가 없으면 실행을 실패시켜 빈 폴더가 자동 생성되지 않도록 했다.

`integration/analyzer/noteu-routes.toml`의 `[[routes]]` 항목을 분석기 `config/evidence.toml`에 추가한다. 기존 `version = 1`과 주문 서비스 매핑을 유지하고 중복 추가하지 않는다. 이 환경에는 해당 매핑이 이미 등록되어 있다.

- `specification.md`: 기존 개발 문서의 요구사항, 사용자 확인 정책, 코드 관찰, 미확정 정책을 구분한다.
- `existing.feature`: 실제 기존 테스트의 검사 범위를 주석에 담는다. 현재 채팅방 업무 Scenario는 0개이며, 오류 수집용 가상 컨트롤러 테스트를 실제 업무 BDD로 변환하지 않는다.
- `test-inventory.md`: 테스트/측정/수동 기록의 상세 출처와 한계를 사람이 확인한다.
- `source-index.json`: 검토한 소스 18개의 해시와 범위를 기록한다. 이 파일 자체는 LLM에 전달되지 않으며 핵심 출처와 한계는 명세·BDD에도 적혀 있다.

```bash
python3 scripts/verify-analyzer-evidence.py
```

위 명령은 근거를 작성한 뒤 출처 코드가 달라졌는지 검사한다. 코드 해석이나 명세 갱신을 자동으로 하지는 않는다. 변경된 파일이 있으면 근거 문서를 함께 검토한다. 현재 근거 감사는 소스 기준 `9bbbfdb`를 사용한다. 다른 배포에서 발생한 오류를 분석할 때에는 해당 배포 코드와 근거가 맞는지 확인한다.

LLM에는 `specification.md`와 `existing.feature` 원문이 함께 전달된다. 기존 Java 테스트나 세션 파일을 통째로 외부에 전송하지 않는다. 인증·세션 값이 들어 있는 수동 HTTP 요청 파일 역시 원문을 연결하지 않는다.

자료를 수정한 뒤에는 분석기 API를 재시작해야 새 입력에 반영된다. 이미 생성된 작업은 DB에 저장한 당시 스냅샷을 계속 사용한다. 따라서 8번 작업의 결과는 유지되고, 이후 같은 오류가 들어와도 명세·BDD·프롬프트가 바뀌면 새 분석 기준으로 작업을 만든다. 동일한 새 기준의 반복 오류는 다시 한 작업으로 묶인다.

다른 API도 수집될 수 있지만, 분석기에 정확한 서비스·메서드·라우트 매핑이 없으면 `422`로 거절되어 저장되지 않는다. 더 많은 API를 연결하려면 각 API의 명세·BDD와 `[[routes]]` 매핑을 추가한다. 분석기에서 작업을 등록하는 단계에는 LLM 호출이 없으며, LLM 분석은 워커가 대기 작업을 가져갈 때 실행된다.

## 2. 두 Compose 프로젝트가 공유할 네트워크 만들기

네트워크가 아직 없다면 한 번 실행한다.

```bash
docker network create bdd-error-reporting
```

이미 있다면 생성 명령을 다시 실행할 필요가 없다. 각 프로젝트의 기존 `default` 네트워크도 그대로 사용한다. 공유 네트워크에는 noteu 앱과 분석기 API만 연결되며, 각 DB는 기존 네트워크에 남는다.

분석기 프로젝트 폴더에서 다음과 같이 API를 실행한다. 아래 경로는 현재 작업 환경 기준이다.

```bash
cd /Users/kurt/IdeaProjects/bdd-gap-analyzer
docker compose -f compose.yaml \
  -f /Users/kurt/IdeaProjects/noteu/integration/analyzer/compose.noteu.yml \
  up -d --no-deps api
```

분석기 DB가 이미 실행 중인 상태를 전제로 한다. API 컨테이너는 `bdd-analyzer`라는 이름으로 접근할 수 있다. 컨테이너 안의 `localhost`는 해당 컨테이너 자신이므로, noteu의 전송 주소는 `http://bdd-analyzer:8000/events`다.

## 3. 격리된 Docker 환경에서 먼저 확인하기

기존 noteu 설정은 `ddl-auto: create`와 SQL 초기 데이터를 사용한다. 앱을 시작하면 연결한 DB 스키마를 다시 만들기 때문에, 연결 검증은 기존 noteu DB 대신 별도 Compose 프로젝트와 새 볼륨에서 진행한다.

`compose.error-reporting-smoke.yml`은 앱/DB 컨테이너 이름과 포트를 구분한다. 반드시 아래 `-p noteu-error-reporting-smoke`를 포함한다. 이 조합은 `noteu-error-reporting-smoke_noteu-mysql-data` 볼륨을 사용한다. `!override`를 지원하는 Docker Compose가 필요하다.

```bash
cd /Users/kurt/IdeaProjects/noteu
export NOTEU_DEPLOY_SHA="$(git rev-parse HEAD)"
docker compose -p noteu-error-reporting-smoke \
  -f docker-compose.yml \
  -f compose.error-reporting.yml \
  -f compose.error-reporting-smoke.yml \
  up -d --build
```

- 격리된 noteu: `http://127.0.0.1:18080`
- 격리된 MySQL: `127.0.0.1:13307`
- 기능 테스트용 이미지: `noteu-error-reporting:local`

noteu가 시작된 뒤, 로그인 쿠키 없이 아래 요청을 두 번 보낸다.

```bash
curl -i http://127.0.0.1:18080/subjects/1/chats/rooms
curl -i http://127.0.0.1:18080/subjects/1/chats/rooms
```

현재 소스에서는 인증 주체가 없을 때 `memberInfo.getId()`가 실패한다. 이 요청은 기존 500 오류를 재현하며 데이터 생성 요청이 아니다. 전송이 성공하면 분석기에 서로 다른 이벤트 2개가 들어오고, 같은 그룹·배포 SHA·명세·BDD·모델·프롬프트 기준에서는 분석 작업 하나를 재사용한다. 이미 같은 기준의 작업이 있다면 그 작업을 재사용한다.

원래 응답과 비교할 때는 먼저 `NOTEU_ERROR_REPORTING_ENABLED=false`를 지정해 같은 명령을 실행하고 응답을 기록한 뒤, 기본값 `true`로 다시 실행한다. 설정 변경은 컨테이너 재생성으로 반영한다. 비교 과정 역시 격리된 DB만 사용한다.

```bash
NOTEU_ERROR_REPORTING_ENABLED=false docker compose -p noteu-error-reporting-smoke \
  -f docker-compose.yml \
  -f compose.error-reporting.yml \
  -f compose.error-reporting-smoke.yml \
  up -d --no-deps app
```

검증이 끝나면 같은 프로젝트 이름과 세 파일을 지정해 `stop`할 수 있다. 기존 noteu 컨테이너와 분석기 DB에는 영향을 주지 않는다.

## 4. 실제 로컬 noteu 실행에 적용하기

격리 환경에서 확인한 뒤, 기존 noteu DB의 초기화 설정을 검토하고 로컬 실행에 오버레이를 적용한다.

```bash
docker compose -f docker-compose.yml -f compose.error-reporting.yml up -d --build app
```

이 명령은 기본 noteu 프로젝트를 대상으로 하므로 위의 격리 실행 명령과 다르다. 기본 Compose만 사용하면 수집 기능은 활성화되지 않는다.

주요 Spring 설정과 환경 변수는 다음과 같다. Compose에서 사용자가 지정하는 `NOTEU_ERROR_REPORTING_ENABLED`와 `NOTEU_DEPLOY_SHA`는 오버레이가 실제 Spring 환경 변수로 변환한다.

| Spring 설정 | 컨테이너 환경 변수 | 기본값 |
| --- | --- | --- |
| `noteu.error-reporting.enabled` | `NOTEU_ERRORREPORTING_ENABLED` | `false` (오버레이에서는 `true`) |
| `noteu.error-reporting.endpoint` | `NOTEU_ERRORREPORTING_ENDPOINT` | `http://bdd-analyzer:8000/events` |
| `noteu.error-reporting.service` | `NOTEU_ERRORREPORTING_SERVICE` | `noteu` |
| `noteu.error-reporting.environment` | `NOTEU_ERRORREPORTING_ENVIRONMENT` | `local` |
| `noteu.error-reporting.deploy-sha` | `NOTEU_ERRORREPORTING_DEPLOYSHA` | `unknown` (오버레이에서는 지정 SHA 또는 `local-development`) |
| `noteu.error-reporting.queue-capacity` | `NOTEU_ERRORREPORTING_QUEUECAPACITY` | `64` |
| `noteu.error-reporting.connect-timeout` | `NOTEU_ERRORREPORTING_CONNECTTIMEOUT` | `2s` |
| `noteu.error-reporting.request-timeout` | `NOTEU_ERRORREPORTING_REQUESTTIMEOUT` | `5s` |

배포 SHA를 실제 코드 버전에 맞추면 코드가 바뀐 뒤 이전 버전의 분석을 잘못 재사용하는 일을 막을 수 있다. 같은 버전의 동일한 오류가 반복되면 분석기의 기존 그룹화·중복 분석 방지 규칙을 그대로 따른다.

## 테스트 실행

Java 17 환경에서 실행한다.

```bash
./gradlew test
```

수집 테스트는 기존 응답 유지, 원본 예외 보완, 요청당 한 번 전송, 4xx 제외, 비동기 큐와 전송 실패의 격리를 확인한다. 기존 `NoteuApplicationTests.contextLoads`도 전용 `application-test.yml`만 읽도록 분리했다. 테스트는 메모리 H2 DB와 가짜 OAuth 설정을 사용하며 개인 `application.yml`의 DB에는 접속하지 않는다. Docker 검증은 실제 컨테이너 사이의 전달과 분석기 그룹·작업 재사용을 별도로 확인한다.
