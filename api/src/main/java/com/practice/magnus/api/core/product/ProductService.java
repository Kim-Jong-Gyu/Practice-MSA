package com.practice.magnus.api.core.product;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

public interface ProductService {

    Product createProduct(@RequestBody Product body);

    /**
     * Sample usage: curl $HOST:$PORT/product/1?delay=3&faultPercent=20
     *
     * @param productId
     * @param delay 응답 전 인위적으로 지연시킬 시간(초). 타임아웃/서킷 브레이커 테스트용
     * @param faultPercent 예외를 던질 확률(%). 재시도/서킷 브레이커 테스트용
     * @return the product, if found, else null
     */
    @GetMapping(
        value    = "/product/{productId}",
        produces = "application/json")
     Mono<Product> getProduct(
         @PathVariable int productId,
         @RequestParam(value = "delay", required = false, defaultValue = "0") int delay,
         @RequestParam(value = "faultPercent", required = false, defaultValue = "0") int faultPercent
     );

    void deleteProduct(@PathVariable int productId);
}