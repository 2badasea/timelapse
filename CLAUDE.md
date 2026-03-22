# TimeLapse Converter — 프로젝트 스펙

OBS 녹화 영상을 타임랩스로 변환하는 로컬 웹 애플리케이션.

## 기술 스택

| 항목 | 내용 |
|------|------|
| 언어 | Java 17 |
| 프레임워크 | Spring Boot 3.5.11 |
| 빌드 | Gradle |
| 템플릿 | Thymeleaf (단일 페이지, index.html) |
| 영상 처리 | FFmpeg (외부 프로세스 호출) |
| 포트 | 8100 |

## 프로젝트 구조

```
src/main/java/com/bada/timelapse/
├── TimelapseApplication.java   # Spring Boot 진입점
├── VideoController.java        # REST API 엔드포인트
├── VideoService.java           # FFmpeg 호출 및 영상 처리 로직
└── VideoConfig.java            # application.properties 바인딩

src/main/resources/
├── templates/index.html        # 단일 페이지 UI (CSS/JS 인라인 포함)
└── application.properties      # 경로 설정, 포트 등
```

## API 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/` | 메인 페이지 |
| GET | `/files` | 소스 폴더 영상 목록 반환 |
| POST | `/select?path=` | 파일 선택 및 길이 추출 |
| GET | `/duration?speed=` | 예상 출력 길이 + 예상 소요 시간 + 키프레임 모드 여부 반환 |
| POST | `/preview` | 앞 30초 미리보기 추출 |
| GET | `/preview/stream` | 미리보기 MP4 스트리밍 |
| POST | `/convert?speed=&filename=` | 타임랩스 변환 시작 (별도 스레드) |
| GET | `/progress` | SSE로 변환 진행률 실시간 스트리밍 (0~100) |
| POST | `/settings?sourcePath=&outputPath=` | 경로 설정 변경 |
| GET | `/outputPath` | 현재 출력 경로 반환 |

## 기본 경로 설정 (application.properties)

```
소스 폴더:  D:/OBS/RECORDING
임시 폴더:  D:/OBS/timelapse-temp
출력 폴더:  D:/OBS/timelapse-output
FFmpeg:     ffmpeg (PATH에 등록된 것 사용)
```

서버 시작 시 임시 폴더를 자동으로 비운다.

## 배속 옵션

슬라이더 13단계: x1, x2, x4, x8, x16, x32, x64, x128, x256, x512, x1024, x2048, x4096

## FFmpeg 변환 전략 (핵심)

배속에 따라 두 가지 모드로 자동 분기한다.

### 128배속 미만 — 풀 디코딩 모드

```
ffmpeg -hwaccel cuda -i <input>
  -vf "select=not(mod(n,SPEED)),setpts=N/FRAME_RATE/TB"
  -an -c:v h264_nvenc -cq 18 -preset p4
```

- 모든 프레임을 디코딩한 뒤 N번째 프레임만 선택
- 정확한 프레임 타이밍 보장
- 예상 처리속도 기준: 원본 길이 / 30 (CUDA 가속 기준)

### 128배속 이상 — 키프레임 고속 모드

```
ffmpeg -hwaccel cuda -hwaccel_output_format cuda
  -skip_frame noref -discard noref -i <input>
  -vf "setpts=PTS/SPEED" -r 30
  -an -c:v h264_nvenc -cq 18 -preset p4
```

- `-discard noref`: 디스크에서 키프레임 외 패킷 자체를 읽지 않음 (I/O 대폭 감소)
- `-skip_frame noref`: 키프레임(I-frame)만 디코딩
- `-hwaccel_output_format cuda`: 디코딩된 프레임을 GPU 메모리에 유지 (CPU↔GPU 전송 제거)
- `-r 30`: 출력 프레임레이트 30fps 고정
- 예상 처리속도 기준: 원본 길이 / 300

### 예상 소요시간 계산

UI에서 배속 선택 시 즉시 표시. 사양에 따라 실제와 다를 수 있음.

```javascript
// 128배속 이상 (키프레임 모드)
estimatedSec = originalDurationSec / 300;

// 128배속 미만 (풀 디코딩)
estimatedSec = originalDurationSec / 30;
```

## 진행률 추적

- FFmpeg `-progress pipe:1` 옵션으로 `out_time_ms` 값을 실시간 파싱
- SSE(Server-Sent Events)로 프론트에 0.5초 간격 전송
- SSE 타임아웃: 5분

## 인코딩 품질 설정

| 옵션 | 값 | 설명 |
|------|----|------|
| `-c:v` | `h264_nvenc` | NVIDIA GPU 인코더 |
| `-cq` | `18` | 품질 기반 인코딩 (낮을수록 고품질) |
| `-preset` | `p4` | 속도/품질 균형 (p1=최고속, p7=최고품질) |
| `-an` | — | 오디오 제거 (타임랩스 특성상 불필요) |

## 지원 입력 포맷

`.mkv`, `.mp4` (소스 폴더 기준 최신순 정렬)

## OBS 녹화 권장 설정

키프레임 간격: **10초** 이상 또는 **0 (자동)**
→ I-frame 수 감소 → 타임랩스 변환 속도 향상, 파일 크기 감소
→ OBS 설정 > 출력 > 고급 모드 > 녹화 탭 > 인코더 설정에서 변경
