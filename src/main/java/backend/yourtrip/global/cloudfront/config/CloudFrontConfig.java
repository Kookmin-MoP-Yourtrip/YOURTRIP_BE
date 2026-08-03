package backend.yourtrip.global.cloudfront.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;

@Configuration
public class CloudFrontConfig {

    @Value("${s3.access-key}")
    private String accessKey;

    @Value("${s3.secret-key}")
    private String secretKey;

    // CloudFrontUtilities는 API 호출 없이 로컬에서 서명만 계산하므로 자격증명이 필요 없다.
    @Bean
    public CloudFrontUtilities cloudFrontUtilities() {
        return CloudFrontUtilities.create();
    }

    // CloudFront는 리전이 없는 글로벌 서비스라 Region.AWS_GLOBAL을 사용한다.
    @Bean
    public CloudFrontClient cloudFrontClient() {
        AwsBasicCredentials creds = AwsBasicCredentials.create(accessKey, secretKey);

        return CloudFrontClient.builder()
            .region(Region.AWS_GLOBAL)
            .credentialsProvider(StaticCredentialsProvider.create(creds))
            .build();
    }
}
