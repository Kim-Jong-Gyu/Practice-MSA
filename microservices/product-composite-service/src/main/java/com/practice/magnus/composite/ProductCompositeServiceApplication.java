package com.practice.magnus.composite;

import java.util.LinkedHashMap;
import java.util.Map;

import com.practice.magnus.composite.services.ProductCompositeIntegration;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.health.contributor.CompositeReactiveHealthContributor;
import org.springframework.boot.health.contributor.ReactiveHealthContributor;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootApplication(scanBasePackages = "com.practice.magnus")
public class ProductCompositeServiceApplication {

	@Value("${api.common.version}")           String apiVersion;
	@Value("${api.common.title}")             String apiTitle;
	@Value("${api.common.description}")       String apiDescription;
	@Value("${api.common.termsOfServiceUrl}") String apiTermsOfServiceUrl;
	@Value("${api.common.license}")           String apiLicense;
	@Value("${api.common.licenseUrl}")        String apiLicenseUrl;
	@Value("${api.common.contact.name}")      String apiContactName;
	@Value("${api.common.contact.url}")       String apiContactUrl;
	@Value("${api.common.contact.email}")     String apiContactEmail;
	@Value("${api.common.externalDocDesc}")   String apiExternalDocDesc;
	@Value("${api.common.externalDocUrl}")    String apiExternalDocUrl;

	/**
	 * Swagger UI: $HOST:$PORT/openapi/swagger-ui.html
	 */
	@Bean
	public OpenAPI getOpenApiDocumentation() {
		return new OpenAPI()
				.info(new Info()
						.title(apiTitle)
						.description(apiDescription)
						.version(apiVersion)
						.termsOfService(apiTermsOfServiceUrl)
						.contact(new Contact()
								.name(apiContactName)
								.url(apiContactUrl)
								.email(apiContactEmail))
						.license(new License()
								.name(apiLicense)
								.url(apiLicenseUrl)))
				.externalDocs(new ExternalDocumentation()
						.description(apiExternalDocDesc)
						.url(apiExternalDocUrl));
	}

	/**
	 * 코어 서비스 호출용 WebClient.
	 * 서비스 디스커버리(@LoadBalanced) 없이, http://product 같은 서비스 이름을 DNS(docker compose 네트워크 / 쿠버네티스 Service)로 찾는다.
	 */
	@Bean
	public WebClient.Builder webClientBuilder() {
		return WebClient.builder();
	}

	public static void main(String[] args) {
		SpringApplication.run(ProductCompositeServiceApplication.class, args);
	}
}