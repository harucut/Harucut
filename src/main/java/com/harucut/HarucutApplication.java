package com.harucut;

import com.harucut.auth.cookie.CookieProperties;
import com.harucut.auth.jwt.JwtProperties;
import com.harucut.auth.oauth2.unlink.KakaoAuthProperties;
import com.harucut.frame.config.FrameLimitsProperties;
import com.harucut.payment.config.PaymentProperties;
import com.harucut.storage.config.AwsProperties;
import com.harucut.storage.config.StorageProperties;
import com.harucut.subscription.config.PlanPricingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableConfigurationProperties({
        JwtProperties.class,
        CookieProperties.class,
        AwsProperties.class,
        StorageProperties.class,
        FrameLimitsProperties.class,
        PlanPricingProperties.class,
        PaymentProperties.class,
        KakaoAuthProperties.class
})
@SpringBootApplication
public class HarucutApplication {

    public static void main(String[] args) {
        SpringApplication.run(HarucutApplication.class, args);
    }

}
