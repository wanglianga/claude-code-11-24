package cc.royalty.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI royaltyOpenApi() {
        return new OpenAPI().info(new Info()
                .title("音乐版权使用归属与版税结算服务 API")
                .version("1.0.0")
                .description("平台使用明细归属、按使用日合同结算、有理数份额与最大余数法分摊、模拟付款回执。"
                        + "金额一律为最小货币单位整数。Swagger UI: /swagger-ui.html")
                .contact(new Contact().name("royalty-settlement")));
    }
}
