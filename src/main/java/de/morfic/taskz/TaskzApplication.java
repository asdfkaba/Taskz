package de.morfic.taskz;

import de.morfic.taskz.config.RsaKeyProperties;
import de.morfic.taskz.notification.NotificationMailProperties;
import de.morfic.taskz.task.AttachmentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({RsaKeyProperties.class, AttachmentProperties.class, NotificationMailProperties.class})
@EnableScheduling
public class TaskzApplication {

	public static void main(String[] args) {
		SpringApplication.run(TaskzApplication.class, args);
	}
}
