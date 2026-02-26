package com.bada.timelapse;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * FFmpeg를 호출하여 실제 영상 처리를 담당하는 서비스 클래스
 */
@Service
public class VideoService {

    private final VideoConfig videoConfig;

    // 업로드된 원본 파일을 임시 저장할 경로
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/timelapse/";

    // 현재 업로드된 원본 파일 경로 (1개만 유지)
    private String currentUploadedFilePath = null;

    // 현재 변환 진행률 (0~100), SSE에서 읽어감
    private volatile int conversionProgress = 0;

    // 원본 영상 길이 (초 단위)
    private double originalDurationSeconds = 0;
    
    public VideoService(VideoConfig videoConfig) {
        this.videoConfig = videoConfig;
        // 서버 시작 시 임시 폴더 자동 비우기
        clearTempDirectory();
    }
    
    /**
     * 서버 시작 시 임시 폴더 내 파일 전체 삭제
     */
    private void clearTempDirectory() {
        File tempDir = new File(TEMP_DIR);
        if (tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                    System.out.println("[초기화] 임시 파일 삭제: " + file.getName());
                }
            }
        }
        System.out.println("[초기화] 임시 폴더 정리 완료: " + TEMP_DIR);
    }

    /**
     * 업로드된 영상 파일을 임시 저장하고 영상 길이를 추출
     */
    public String uploadVideo(MultipartFile file) throws IOException {
        // 임시 디렉토리 생성
        File tempDir = new File(TEMP_DIR);
        if (!tempDir.exists()) tempDir.mkdirs();

        // 기존 업로드 파일 삭제 (1개만 유지)
        if (currentUploadedFilePath != null) {
            new File(currentUploadedFilePath).delete();
        }

        // 원본 파일명 유지하면서 UUID로 중복 방지
        String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path savePath = Paths.get(TEMP_DIR + filename);
        Files.write(savePath, file.getBytes());

        currentUploadedFilePath = savePath.toString();

        // FFmpeg로 영상 길이 추출
        originalDurationSeconds = extractDuration(currentUploadedFilePath);

        return currentUploadedFilePath;
    }

    /**
     * FFmpeg ffprobe 명령어로 영상 길이(초)를 추출
     */
    public double extractDuration(String filePath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                filePath
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes()).trim();

        try {
            return Double.parseDouble(output);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 배속에 따른 예상 출력 영상 길이(초) 계산
     */
    public double calculateOutputDuration(int speed) {
        return originalDurationSeconds / speed;
    }

    public String generatePreview() throws IOException, InterruptedException {
        if (currentUploadedFilePath == null) throw new IllegalStateException("업로드된 파일이 없습니다.");

        String previewPath = TEMP_DIR + "preview.mp4";

        ProcessBuilder pb = new ProcessBuilder(
                videoConfig.getFfmpegPath(),
                "-y",
                "-ss", "0",
                "-t", "30",
                "-i", currentUploadedFilePath,
                "-an",
                "-crf", "18",
                "-preset", "fast",
                previewPath
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // waitFor() 전에 스트림을 먼저 읽어야 버퍼가 막히지 않음
        String ffmpegLog = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();

        System.out.println("=== FFmpeg 미리보기 로그 ===");
        System.out.println(ffmpegLog);
        System.out.println("===========================");

        return previewPath;
    }

    /**
     * 전체 영상을 선택한 배속으로 변환 (CUDA 하드웨어 가속 + 진행률 계산)
     */
    public String convertVideo(int speed, String originalFilename) throws IOException, InterruptedException {
        if (currentUploadedFilePath == null) throw new IllegalStateException("업로드된 파일이 없습니다.");

        File outputDir = new File(videoConfig.getOutputPath());
        if (!outputDir.exists()) outputDir.mkdirs();

        String baseName = originalFilename.replaceAll("\\.[^.]+$", "");
        String outputPath = videoConfig.getOutputPath() + "/" + baseName + "_" + speed + "x.mp4";

        ProcessBuilder pb = new ProcessBuilder(
                videoConfig.getFfmpegPath(),
                "-y",
                "-hwaccel", "cuda",
                "-i", currentUploadedFilePath,
                "-vf", "setpts=PTS/" + speed,
                "-an",
                "-c:v", "h264_nvenc",
                "-cq", "18",
                "-preset", "p4",
                "-progress", "pipe:1",         // 진행률을 표준출력으로 출력
                "-nostats",
                outputPath
        );
        pb.redirectErrorStream(false);         // 진행률 파싱을 위해 false로 설정
        Process process = pb.start();

        // 진행률 파싱 스레드 (FFmpeg 출력을 읽으면서 진행률 업데이트)
        final double totalDuration = originalDurationSeconds;
        Thread progressThread = new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // FFmpeg -progress 옵션은 "out_time_ms=숫자" 형식으로 현재 처리 시간을 출력
                    if (line.startsWith("out_time_ms=")) {
                        String value = line.split("=")[1].trim();
                        try {
                            double currentTimeSec = Long.parseLong(value) / 1_000_000.0;
                            int percent = (int) Math.min((currentTimeSec / totalDuration) * 100, 99);
                            // 진행률을 전역 변수에 저장 (SSE에서 읽어감)
                            conversionProgress = percent;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("진행률 읽기 오류: " + e.getMessage());
            }
        });
        progressThread.start();

        // 에러 스트림도 읽어서 버퍼 막힘 방지
        Thread errorThread = new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[FFmpeg] " + line);
                }
            } catch (IOException e) {
                System.out.println("에러 스트림 읽기 오류: " + e.getMessage());
            }
        });
        errorThread.start();

        process.waitFor();
        progressThread.join();
        errorThread.join();

        // 변환 완료 시 진행률 100으로 설정
        conversionProgress = 100;

        return outputPath;
    }

    // 현재 업로드된 파일 경로 반환
    public String getCurrentUploadedFilePath() {
        return currentUploadedFilePath;
    }

    // 원본 영상 길이 반환
    public double getOriginalDurationSeconds() {
        return originalDurationSeconds;
    }

    // 출력 경로 업데이트 (설정 화면에서 변경 시 사용)
    public void updateOutputPath(String newPath) {
        videoConfig.setOutputPath(newPath);
    }

    public String getOutputPath() {
        return videoConfig.getOutputPath();
    }

    // 미리보기 파일 경로 반환
    public String getPreviewPath() {
        return TEMP_DIR + "preview.mp4";
    }

    // 현재 변환 진행률 반환
    public int getConversionProgress() {
        return conversionProgress;
    }

    // 진행률 초기화
    public void resetProgress() {
        conversionProgress = 0;
    }
}
