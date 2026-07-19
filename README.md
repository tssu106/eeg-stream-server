# eeg-stream-server

EDF(European Data Format) 수면다원검사(PSG) 파일을 파싱하고, 채널별 파형을 브라우저에서 스크롤하며 볼 수 있는 뷰어를 제공하는 프로젝트입니다.

## 프로젝트 구조

```
eeg-stream-server/
├── src/main/java/eeg/edf/       # EDF 헤더/신호 파서 (EdfHeader, EdfSignalHeader, EdfReader)
├── src/main/java/eeg/server/    # EdfReader를 감싸는 HTTP 서버 (EdfServer)
├── src/main/resources/eeg/server/viewer.html  # 브라우저 뷰어 프론트엔드
├── src/test/java/eeg/edf/       # EdfReader 검증 테스트
├── src/test/resources/          # MNE로 생성한 헤더/샘플 정답지
├── data/                        # EDF 원본 파일 (git 제외)
└── notebooks/                   # MNE 기반 정답지 생성용 Python 노트북
```

## 요구 사항

- JDK 21
- 검증용 EDF 파일: `data/SC4001E0-PSG.edf` (git에서 제외되어 있으므로 직접 준비해서 `data/` 폴더에 위치시켜야 합니다)

## 빌드 & 테스트

```
./gradlew test
```

`data/SC4001E0-PSG.edf` 파일이 없으면 `EdfReaderTest`는 자동으로 스킵됩니다.

> Windows에서 사용자 홈 경로에 비ASCII 문자(한글 등)가 포함되어 있으면 Gradle 테스트 워커 프로세스가 뜨지 못할 수 있습니다. 이 경우 `GRADLE_USER_HOME`을 ASCII 경로로 지정하고 실행하세요.
> ```
> $env:GRADLE_USER_HOME = "C:\gradle-home"
> .\gradlew.bat test
> ```

## 서버 실행

```
./gradlew run
```

기본적으로 `data/SC4001E0-PSG.edf`를 읽어 `http://localhost:8080`에서 서비스합니다. 다른 파일/포트를 쓰려면:

```
./gradlew run --args="data/다른파일.edf 9090"
```

## API

- `GET /api/header` — 채널 목록(라벨, 단위, 샘플링 주파수, 카테고리), 레코드 수, 총 녹음 길이 반환
- `GET /api/samples?channel={index}&start={sec}&duration={sec}` — 지정한 채널의 구간 샘플(물리값) 반환

## 뷰어 사용법

브라우저에서 `http://localhost:8080` 접속 후:

- 카테고리 탭: `EEG` / `EOG` / `EMG` / `Resp` / `Temp` / `Event`
- 시간 창 프리셋: `30초` / `1분` / `10분`
- 슬라이더로 전체 녹음 구간(수 시간~수십 시간) 내 임의 위치로 이동
- `←` / `→` 방향키로 현재 창 길이만큼 앞뒤 구간으로 이동
