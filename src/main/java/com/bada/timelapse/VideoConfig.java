package com.bada.timelapse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * application.properties의 app.* 설정값을 읽어오는 설정 클래스
 */
@Configuration
@ConfigurationProperties(prefix = "app")
public class VideoConfig {

    // 출력 파일이 저장될 경로 (사용자가 설정 화면에서 변경 가능)
    private String outputPath = "C:/timelapse-output";

    // FFmpeg 실행 파일 경로 (기본값: 시스템 PATH에 등록된 ffmpeg)
    private String ffmpegPath = "ffmpeg";

    // outputPath getter/setter
    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    // ffmpegPath getter/setter
    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }
}