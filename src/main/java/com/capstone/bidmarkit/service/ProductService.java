package com.capstone.bidmarkit.service;

import com.capstone.bidmarkit.domain.*;
import com.capstone.bidmarkit.dto.*;
import com.capstone.bidmarkit.repository.BidRepository;
import com.capstone.bidmarkit.repository.ProductImgRepository;
import com.capstone.bidmarkit.repository.ProductRepository;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class ProductService {
    private final ProductRepository productRepository;
    private final ProductImgRepository productImgRepository;
    private final BidRepository bidRepository;
    private final Storage storage;

    @Value("${spring.cloud.gcp.storage.bucket}")
    private String bucketName;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public AddProductResponse save(String memberId, AddProductRequest dto) throws IOException {
        Product newProduct = productRepository.save(
                Product.builder()
                        .memberId(memberId)
                        .name(dto.getProductName())
                        .category(dto.getCategory())
                        .content(dto.getContent())
                        .initPrice(dto.getInitPrice())
                        .price(dto.getPrice())
                        .deadline(LocalDateTime.now().plusDays(dto.getDeadline()))
                        .build()
        );

        List<ProductImg> images = new ArrayList<>();
        for (int i = 0; i < dto.getImages().size(); i++) {
            MultipartFile image = dto.getImages().get(i);
            String uuid = UUID.randomUUID().toString();
            BlobInfo blobInfo = storage.create(
                    BlobInfo.newBuilder(bucketName, uuid)
                            .setContentType(image.getContentType())
                            .build(),
                    image.getInputStream()
            );

            ProductImg newImage = productImgRepository.save(
                    ProductImg.builder()
                            .product(newProduct)
                            .imgUrl("https://storage.googleapis.com/" + bucketName + '/' + uuid)
                            .isThumbnail(i == 0)
                            .build()
            );
            images.add(newImage);
        }

        newProduct.setImages(images);
        return new AddProductResponse(newProduct.getId());
    }

    public Page<ProductBriefResponse> findAllOrderByDeadlineAsc(Pageable pageable) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QProduct product = QProduct.product;
        QProductImg productImg = QProductImg.productImg;

        List<ProductBriefResponse> results = queryFactory
                .select(
                        Projections.constructor(
                        ProductBriefResponse.class,
                        productImg.imgUrl, product.name, product.category, product.id,
                        product.bidPrice, product.price, product.state, product.deadline)
                )
                .from(product)
                .leftJoin(product.images, productImg)
                .groupBy(product.id, productImg.imgUrl)
                .where(productImg.isThumbnail.isTrue())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(results, pageable, queryFactory.select(product.count()).from(product)::fetchCount);
    }

    public Page<GetPurchaseResponse> findAllPurchased(String memberId, Pageable pageable) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QProduct product = QProduct.product;
        QProductImg productImg = QProductImg.productImg;
        QTrade trade = QTrade.trade;

        List<GetPurchaseResponse> results = queryFactory
                .select(
                        Projections.constructor(
                                GetPurchaseResponse.class,
                                productImg.imgUrl, product.name, product.id, trade.price.avg().intValue())
                )
                .from(trade)
                .where(trade.buyerId.eq(memberId))
                .leftJoin(trade.product, product)
                .leftJoin(product.images, productImg)
                .groupBy(product.id, productImg.imgUrl)
                .where(productImg.isThumbnail.isTrue())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(results, pageable, queryFactory.select(trade.count()).from(trade).where(trade.buyerId.eq(memberId))::fetchCount);
    }

    public Page<GetSaleResponse> findAllSale(String memberId, int state, Pageable pageable) {
        JPAQueryFactory queryFactory = new JPAQueryFactory(entityManager);
        QProduct product = QProduct.product;
        QProductImg productImg = QProductImg.productImg;

        BooleanBuilder whereClause = new BooleanBuilder(product.memberId.eq(memberId));
        if(state != 4) whereClause.and(product.state.eq(state));

        List<GetSaleResponse> results = queryFactory
                .select(
                        Projections.constructor(
                                GetSaleResponse.class,
                                productImg.imgUrl, product.name, product.id,
                                product.bidPrice, product.state)
                )
                .from(product)
                .where(whereClause)
                .leftJoin(product.images, productImg)
                .groupBy(product.id, productImg.imgUrl)
                .where(productImg.isThumbnail.isTrue())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(results, pageable, queryFactory.select(product.count()).from(product).where(whereClause)::fetchCount);
    }

    public ProductDetailResponse findDetail(int productId) {
        ProductDetailResponse res = new ProductDetailResponse();

        Product a = productRepository.findDetailById(productId);

        res.setImages(productImgRepository.findByProductId(productId)
                .stream()
                .map(ProductImg::getImgUrl)
                .collect(Collectors.toList()));
        res.setProductName(a.getName());
        res.setCategory(a.getCategory());
        res.setBidPrice(a.getBidPrice());
        res.setInitPrice(a.getInitPrice());
        res.setPrice(a.getPrice());
        res.setState(a.getState());
        res.setDeadline(a.getDeadline());
        res.setSellerName(a.getMemberId());
        res.setContent(a.getContent());

        return res;
    }

    @Transactional
    public void purchaseProduct(String memberId, int productId) {
        Product product = productRepository.findById(productId).orElseThrow(() -> new IllegalArgumentException("Product not found"));

        // 상품 상태가 판매 중이 아닐 경우, 예외 발생
        if(product.getState() != 0)
            throw new IllegalArgumentException("It is not a purchasable product");

        product.setState(1);
        product.setBidPrice(product.getPrice());
        bidRepository.save(Bid.builder()
                .productId(productId)
                .memberId(memberId)
                .price(product.getPrice())
                .build()
        );
    }
}
