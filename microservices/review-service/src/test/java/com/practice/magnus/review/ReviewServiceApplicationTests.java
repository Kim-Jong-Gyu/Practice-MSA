package com.practice.magnus.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static com.practice.magnus.api.event.Event.Type.CREATE;
import static com.practice.magnus.api.event.Event.Type.DELETE;

import java.util.function.Consumer;
import com.practice.magnus.api.core.review.Review;
import com.practice.magnus.api.event.Event;
import com.practice.magnus.review.persistence.ReviewRepository;
import com.practice.magnus.util.exceptions.InvalidInputException;
import com.practice.magnus.util.test.MySqlTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

@DisplayName("Review 서비스 통합 테스트")
@SpringBootTest(
		webEnvironment = RANDOM_PORT,
		properties = {
				"logging.level.com.practice.magnus=DEBUG",
				"spring.jpa.hibernate.ddl-auto=create-drop"
		})
@AutoConfigureWebTestClient
class ReviewServiceApplicationTests extends MySqlTestBase {

	@Autowired
	private WebTestClient client;

	@Autowired
	private ReviewRepository repository;

	@Autowired
	@Qualifier("messageProcessor")
	private Consumer<Event<Integer, Review>> messageProcessor;

	@BeforeEach
	void setupDb() {
		repository.deleteAll();
	}

	@Test
	@DisplayName("CREATE 이벤트 3건을 받으면 리뷰 3건이 저장되고 API로 조회할 수 있다")
	void createEvents_thenReviewsCanBeFetched() {
		int productId = 1;

		assertThat(repository.findByProductId(productId)).isEmpty();

		sendCreateReviewEvent(productId, 1);
		sendCreateReviewEvent(productId, 2);
		sendCreateReviewEvent(productId, 3);

		assertThat(repository.findByProductId(productId)).hasSize(3);

		getAndVerifyReviewsByProductId(productId, OK)
				.jsonPath("$.length()").isEqualTo(3)
				.jsonPath("$[2].productId").isEqualTo(productId)
				.jsonPath("$[2].reviewId").isEqualTo(3);
	}

	@Test
	@DisplayName("같은 productId와 reviewId로 CREATE 이벤트를 두 번 받으면 InvalidInputException이 발생한다")
	void duplicateCreateEvent_thenThrowsInvalidInput() {
		int productId = 1;
		int reviewId = 1;

		assertThat(repository.count()).isZero();

		sendCreateReviewEvent(productId, reviewId);

		assertThat(repository.count()).isEqualTo(1);

		assertThatThrownBy(() -> sendCreateReviewEvent(productId, reviewId))
				.isInstanceOf(InvalidInputException.class)
				.hasMessage("Duplicate key, Product Id: 1, Review Id:1");

		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("DELETE 이벤트를 받으면 리뷰가 삭제되고, 없는 리뷰를 삭제해도 에러가 나지 않는다")
	void deleteEvent_thenReviewsRemoved() {
		int productId = 1;
		int reviewId = 1;

		sendCreateReviewEvent(productId, reviewId);
		assertThat(repository.findByProductId(productId)).hasSize(1);

		sendDeleteReviewEvent(productId);
		assertThat(repository.findByProductId(productId)).isEmpty();

		sendDeleteReviewEvent(productId);   // 멱등성 확인: 두 번째 삭제도 예외 없이 통과해야 함
	}

	@Test
	@DisplayName("productId 파라미터가 없으면 400 Bad Request를 반환한다")
	void getReviews_withoutProductId_thenBadRequest() {
		getAndVerifyReviewsByProductId("", BAD_REQUEST)
				.jsonPath("$.path").isEqualTo("/review");
	}

	@Test
	@DisplayName("productId가 숫자가 아니면 400 Bad Request를 반환한다")
	void getReviews_withNonNumericProductId_thenBadRequest() {
		getAndVerifyReviewsByProductId("?productId=no-integer", BAD_REQUEST)
				.jsonPath("$.path").isEqualTo("/review");
	}

	@Test
	@DisplayName("리뷰가 없는 productId면 빈 배열을 반환한다")
	void getReviews_withUnknownProductId_thenEmptyList() {
		getAndVerifyReviewsByProductId("?productId=213", OK)
				.jsonPath("$.length()").isEqualTo(0);
	}

	@Test
	@DisplayName("productId가 음수면 422 Unprocessable Entity를 반환한다")
	void getReviews_withNegativeProductId_thenUnprocessableEntity() {
		int productIdInvalid = -1;

		getAndVerifyReviewsByProductId("?productId=" + productIdInvalid, UNPROCESSABLE_CONTENT)
				.jsonPath("$.path").isEqualTo("/review")
				.jsonPath("$.message").isEqualTo("Invalid productId: " + productIdInvalid);
	}

	private WebTestClient.BodyContentSpec getAndVerifyReviewsByProductId(int productId, HttpStatus expectedStatus) {
		return getAndVerifyReviewsByProductId("?productId=" + productId, expectedStatus);
	}

	private WebTestClient.BodyContentSpec getAndVerifyReviewsByProductId(String productIdQuery, HttpStatus expectedStatus) {
		return client.get()
				.uri("/review" + productIdQuery)
				.accept(APPLICATION_JSON)
				.exchange()
				.expectStatus().isEqualTo(expectedStatus)
				.expectHeader().contentType(APPLICATION_JSON)
				.expectBody();
	}

	private void sendCreateReviewEvent(int productId, int reviewId) {
		Review review = new Review(productId, reviewId,
				"Author " + reviewId, "Subject " + reviewId, "Content " + reviewId, "SA");
		Event<Integer, Review> event = new Event<>(CREATE, productId, review);
		messageProcessor.accept(event);
	}

	private void sendDeleteReviewEvent(int productId) {
		Event<Integer, Review> event = new Event<>(DELETE, productId, null);
		messageProcessor.accept(event);
	}
}