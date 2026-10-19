package com.practice.magnus.product;


import com.practice.magnus.api.core.product.Product;
import com.practice.magnus.product.persistence.ProductEntity;
import com.practice.magnus.product.services.ProductMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.*;

public class MapperTests {

    private ProductMapper mapper = Mappers.getMapper(ProductMapper.class);

    @Test
    public void mapperTests() {

        assertNotNull(mapper);

        Product api = new Product(1, "n", 1, "sa");

        ProductEntity entity = mapper.apiToEntity(api);

        assertEquals(api.getProductId(), entity.getProductId());
        assertEquals(api.getProductId(), entity.getProductId());
        assertEquals(api.getName(), entity.getName());
        assertEquals(api.getWeight(), entity.getWeight());

        Product api2 = mapper.entityToApi(entity);

        assertEquals(api.getProductId(), api2.getProductId());
        assertEquals(api.getProductId(), api2.getProductId());
        assertEquals(api.getName(),      api2.getName());
        assertEquals(api.getWeight(),    api2.getWeight());
        assertNull(api2.getServiceAddress());
    }
}
