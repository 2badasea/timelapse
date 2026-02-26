package com.bada.timelapse;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * 브라우저의 요청을 받아서 처리하고 응답을 반환하는 컨트롤러 클래스
 */
@Controller
public class VideoController {

    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    /**
     * 메인 페이지 요청 처리 (GET /)
     */
    @GetMapping("/")
    public String index(Model model) {
        // 현재 출력 경로를 화면에 전달
        model.addAttribute("outputPath", videoService.getOutputPath());
        return "index"; // templates/index.html 렌더링
    }

    /**
     * 영상 파일 업로드 처리 (POST /upload)
     * 파일을 임시 저장하고 영상 길이를 반환
     */
    @PostMapping("/upload")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadVideo(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();
        try {
            videoService.uploadVideo(file);

            // 업로드 성공 시 영상 길이와 파일명 반환
            response.put("success", true);
            response.put("duration", videoService.getOriginalDurationSeconds());
            response.put("filename", file.getOriginalFilename());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 예상 출력 길이 계산 (GET /duration?speed=숫자)
     */
    @GetMapping("/duration")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getDuration(@RequestParam int speed) {
        Map<String, Object> response = new HashMap<>();
        try {
            double outputDuration = videoService.calculateOutputDuration(speed);
            response.put("success", true);
            response.put("outputDuration", outputDuration);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 미리보기 영상 생성 (POST /preview)
     * 배속 미적용 원본 앞 10초 추출 - 브라우저에서 재생속도 조절
     */
    @PostMapping("/preview")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generatePreview() {
        Map<String, Object> response = new HashMap<>();
        try {
            String previewPath = videoService.generatePreview();
            response.put("success", true);
            response.put("previewPath", previewPath);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 미리보기 영상 파일을 브라우저로 스트리밍 (GET /preview/stream)
     */
    @GetMapping("/preview/stream")
    @ResponseBody
    public ResponseEntity<byte[]> streamPreview() {
        try {
            String previewPath = videoService.getPreviewPath();
            java.io.File file = new java.io.File(previewPath);
            if (!file.exists()) return ResponseEntity.notFound().build();

            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return ResponseEntity.ok()
                    .header("Content-Type", "video/mp4")
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 최종 영상 변환 및 저장 (POST /convert?speed=숫자&filename=파일명)
     */
    @PostMapping("/convert")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> convertVideo(
            @RequestParam int speed,
            @RequestParam String filename) {
        Map<String, Object> response = new HashMap<>();
        try {
            // 진행률 초기화
            videoService.resetProgress();

            // 변환은 별도 스레드에서 실행 (SSE가 진행률을 읽을 수 있도록)
            new Thread(() -> {
                try {
                    videoService.convertVideo(speed, filename);
                } catch (Exception e) {
                    System.out.println("변환 오류: " + e.getMessage());
                }
            }).start();

            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 변환 진행률을 실시간으로 브라우저에 전달 (GET /progress)
     * SSE(Server-Sent Events) 방식으로 진행률을 스트리밍
     */
    @GetMapping("/progress")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> streamProgress() {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(300_000L); // 5분 타임아웃

        new Thread(() -> {
            try {
                while (true) {
                    int progress = videoService.getConversionProgress();

                    // 진행률 전송
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                            .event().data(progress));

                    // 100% 완료 시 종료
                    if (progress >= 100) {
                        emitter.complete();
                        break;
                    }

                    // 0.5초마다 진행률 체크
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();

        return ResponseEntity.ok()
                .header("Content-Type", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .body(emitter);
    }

    /**
     * 출력 경로 설정 변경 (POST /settings?outputPath=경로)
     */
    @PostMapping("/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestParam String outputPath) {
        Map<String, Object> response = new HashMap<>();
        try {
            videoService.updateOutputPath(outputPath);
            response.put("success", true);
            response.put("outputPath", outputPath);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 현재 출력 경로 반환 (GET /outputPath)
     * 변환 완료 후 저장 경로를 화면에 표시하기 위해 사용
     */
    @GetMapping("/outputPath")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getOutputPath() {
        Map<String, Object> response = new HashMap<>();
        response.put("outputPath", videoService.getOutputPath());
        return ResponseEntity.ok(response);
    }

}
