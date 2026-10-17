package com.logistics.inventory.db.product;

import com.logistics.inventory.domain.product.entity.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProductJpaRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByCompanyCodeValueAndProductCodeValue(String companyCode, String productCode);

    // 같은 코드가 이미 있으면(다른 트랜잭션이 넣는 중이면 커밋을 기다린 뒤) 오류 없이 넣지 않는다. 넣은 행 수(0 또는 1)를 돌려준다
    @Modifying
    @Query(value = """
            INSERT INTO product (company_code, product_code, name)
            VALUES (:companyCode, :productCode, :name)
            ON CONFLICT (company_code, product_code) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("companyCode") String companyCode, @Param("productCode") String productCode, @Param("name") String name);
}
