package com.bada.timelapse;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FFmpeg를 호출하여 실제 영상 처리를 담당하는 서비스 클래스
 */
@Service
public class VideoService {

    private final VideoConfig videoConfig;

    // 업로드된 원본 파일을 임시 저장할 경로
    private final String TEMP_DIR;

    // 현재 업로드된 원본 파일 경로 (1개만 유지)
    private String currentUploadedFilePath = null;

    // 현재 변환 진행률 (0~100), SSE에서 읽어감
    private volatile int conversionProgress = 0;

    // 원본 영상 길이 (초 단위)
    private double originalDurationSeconds = 0;
    
    public VideoService(VideoConfig videoConfig) {
        this.videoConfig = videoConfig;
        this.TEMP_DIR = videoConfig.getTempPath() + "/";
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
     * 소스 폴더의 mkv/mp4 파일 목록을 최신순으로 반환
     */
    public List<Map<String, Object>> listSourceFiles() {
        File sourceDir = new File(videoConfig.getSourcePath());
        List<Map<String, Object>> files = new ArrayList<>();
        if (!sourceDir.exists() || !sourceDir.isDirectory()) return files;

        File[] videoFiles = sourceDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".mkv") || lower.endsWith(".mp4");
        });

        if (videoFiles != null) {
            Arrays.sort(videoFiles, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : videoFiles) {
                Map<String, Object> info = new HashMap<>();
                info.put("name", f.getName());
                info.put("size", f.length());
                info.put("path", f.getAbsolutePath());
                info.put("lastModified", f.lastModified());
                files.add(info);
            }
        }
        return files;
    }

    /**
     * 파일 경로로 직접 선택 - 복사 없이 경로만 저장하고 영상 길이 추출
     */
    public void selectFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) throw new IllegalArgumentException("파일을 찾을 수 없습니다: " + filePath);
        currentUploadedFilePath = filePath;
        originalDurationSeconds = extractDuration(filePath);
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

    // 소스 경로 업데이트
    public void updateSourcePath(String newPath) {
        videoConfig.setSourcePath(newPath);
    }

    public String getSourcePath() {
        return videoConfig.getSourcePath();
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
