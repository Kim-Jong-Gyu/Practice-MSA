package com.practice.magnus.review.services;

import com.practice.magnus.api.core.review.Review;
import com.practice.magnus.review.persistence.ReviewEntity;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-10-20T02:19:03+0900",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.12.1 (Amazon.com Inc.)"
)
@Component
public class ReviewMapperImpl implements ReviewMapper {

    @Override
    public Review entityToApi(ReviewEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Review review = new Review();

        review.setProductId( entity.getProductId() );
        review.setReviewId( entity.getReviewId() );
        review.setAuthor( entity.getAuthor() );
        review.setSubject( entity.getSubject() );
        review.setContent( entity.getContent() );

        return review;
    }

    @Override
    public ReviewEntity apiToEntity(Review api) {
        if ( api == null ) {
            return null;
        }

        ReviewEntity reviewEntity = new ReviewEntity();

        reviewEntity.setProductId( api.getProductId() );
        reviewEntity.setReviewId( api.getReviewId() );
        reviewEntity.setAuthor( api.getAuthor() );
        reviewEntity.setSubject( api.getSubject() );
        reviewEntity.setContent( api.getContent() );

        return reviewEntity;
    }

    @Override
    public List<Review> entityListToApiList(List<ReviewEntity> entity) {
        if ( entity == null ) {
            return null;
        }

        List<Review> list = new ArrayList<Review>( entity.size() );
        for ( ReviewEntity reviewEntity : entity ) {
            list.add( entityToApi( reviewEntity ) );
        }

        return list;
    }

    @Override
    public List<ReviewEntity> apiListToEntityList(List<Review> api) {
        if ( api == null ) {
            return null;
        }

        List<ReviewEntity> list = new ArrayList<ReviewEntity>( api.size() );
        for ( Review review : api ) {
            list.add( apiToEntity( review ) );
        }

        return list;
    }
}
