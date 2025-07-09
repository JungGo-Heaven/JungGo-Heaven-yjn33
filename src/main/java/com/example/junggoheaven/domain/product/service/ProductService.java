package com.example.junggoheaven.domain.product.service;


import com.example.junggoheaven.domain.bespokeinfo.service.BespokeInfoService;
import com.example.junggoheaven.domain.image.entity.ProductImage;
import com.example.junggoheaven.domain.image.exception.UnexpectedErrorException;
import com.example.junggoheaven.domain.image.repository.ProductImageRepository;
import com.example.junggoheaven.domain.location.dto.GeoCoordinate;
import com.example.junggoheaven.domain.location.dto.LocationVerificationRequest;
import com.example.junggoheaven.domain.location.exception.LocationVerificationRequiredException;
import com.example.junggoheaven.domain.location.service.GeoService;
import com.example.junggoheaven.domain.location.service.LocationVerificationService;
import com.example.junggoheaven.domain.location.util.GeoUtil;
import com.example.junggoheaven.domain.product.dto.request.ProductRequestDto;
import com.example.junggoheaven.domain.product.dto.request.ProductSellStatusRequestDto;
import com.example.junggoheaven.domain.product.dto.response.ProductResponseDto;
import com.example.junggoheaven.domain.product.entity.Product;
import com.example.junggoheaven.domain.product.enums.SellStatus;
import com.example.junggoheaven.domain.product.exception.ProductNotYourException;
import com.example.junggoheaven.domain.product.exception.ProductSellStatusSameFlag;
import com.example.junggoheaven.domain.product.service.component.ProductChecker;
import com.example.junggoheaven.domain.product.service.component.ProductFinder;
import com.example.junggoheaven.domain.product.service.component.ProductWriter;
import com.example.junggoheaven.domain.product.sync.SyncPullAt;
import com.example.junggoheaven.domain.user.entity.User;
import com.example.junggoheaven.domain.user.service.component.UserFinder;
import com.example.junggoheaven.global.auth.dto.user.AuthUser;
import com.example.junggoheaven.global.message.event.finder.ProductRegisteredEvent;
import com.example.junggoheaven.global.message.publisher.EventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

	private final ProductFinder productFinder;
	private final ProductWriter productWriter;
	private final ProductChecker productChecker;

	private final UserFinder userFinder;

	private final ProductImageRepository productImageRepository;

	private final EventPublisher eventPublisher;

	private final GeoService geoService;

	private final LocationVerificationService locationVerificationService;

	private final BespokeInfoService bespokeInfoService;


	/*
		상품 등록 메서드
	*/
	@Transactional
	public ProductResponseDto saveProduct(AuthUser authUser, ProductRequestDto productRequestDto) {

		User user = userFinder.findByUserId(authUser.getId());
		GeoCoordinate currentLocation = geoService.getGeoData(productRequestDto.getAddress());

		LocationVerificationRequest request = LocationVerificationRequest.of(
				currentLocation.getLongitude(),
				currentLocation.getLatitude(),
				user.getId());

		boolean isVerified = locationVerificationService.authenticateLocation(request);

		if (!isVerified) {
			throw new LocationVerificationRequiredException();
		}

		// 인증 통과 후 lastVerifiedAt 업데이트
		locationVerificationService.updateLastVerified(user);

		//이하 Product 저장 로직
		// Point는 항상 경도,위도 순으로 저장
		Point location = GeoUtil.createPoint(currentLocation.getLongitude(), currentLocation.getLatitude());

		if(productRequestDto.getProductImageId() == null){

			Product product = new Product(
				user,
				productRequestDto.getName(),
				productRequestDto.getInformation(),
				productRequestDto.getPrice(),
				null,
					productRequestDto.getAddress(),
					currentLocation.getLongitude(),
					currentLocation.getLatitude(),
					location,
				productRequestDto.getProductCategory() // 카테고리 추가
			);

			productWriter.saveProduct(product);
			eventPublisher.publishEventAfterTransaction(new ProductRegisteredEvent(this, user.getId(), product.getName()));

			// 상품 조회 로그 스냅샷 저장
			if(bespokeInfoService.isBespokeAgree(authUser)) {
				bespokeInfoService.saveProductLog(authUser, product);
			}


			return new ProductResponseDto(product);
		}


		ProductImage productImage = productImageRepository.findById(productRequestDto.getProductImageId())
			.orElseThrow(UnexpectedErrorException::new);


		Product product = new Product(
				user,
				productRequestDto.getName(),
				productRequestDto.getInformation(),
				productRequestDto.getPrice(),
				productImage,
				productRequestDto.getAddress(),
				currentLocation.getLongitude(),
				currentLocation.getLatitude(),
				location,
			productRequestDto.getProductCategory()
		);

		productWriter.saveProduct(product);

		eventPublisher.publishEventAfterTransaction(new ProductRegisteredEvent(this, user.getId(), product.getName()));

		// 상품 조회 로그 스냅샷 저장
		if(bespokeInfoService.isBespokeAgree(authUser)) {
			bespokeInfoService.saveProductLog(authUser, product);
		}

		return new ProductResponseDto(product);
	}


	/*
		상품 다건 페이지네이션 조회 메서드
	*/
	@Transactional(readOnly = true)
	public Page<ProductResponseDto> findAllProduct(int page, int size) {
		// Refactor 고민 요망
		Pageable pageable = PageRequest.of( (page > 0) ? page - 1 : 0, size, Sort.by("pullAt").descending());

		Page<Product> productPage = productFinder.findAllProduct(pageable);

		List<ProductResponseDto> dtoList = productPage.getContent().stream()
			.map(ProductResponseDto::toDto)
			.toList();

		return new PageImpl<>(dtoList, pageable, productPage.getTotalElements());
	}


	/*
		상품 단건 조회 메서드
	*/
	@Transactional
	public ProductResponseDto findProductById(AuthUser authUser, Long productId) {

		Product product = productFinder.findProductById(productId);

		// 상품 조회 로그 스냅샷 저장
		if(bespokeInfoService.isBespokeAgree(authUser)) {
			bespokeInfoService.saveProductLog(authUser, product);
		}

		return new ProductResponseDto(product);
	}


	/*
		상품 소프트딜리트 메서드
	*/
	@Transactional
	public ProductResponseDto softDeleteProduct(Long id) {

		Product product = productFinder.findProductById(id);

		product.softDeleteSetDateTime(); // 소프트 딜리트 변수에 현재시간 대입 -> null 이 아니므로 더이상 DB에 레코드가 논리적으로 존재하지 않음

		productWriter.deleteProduct(product);

		return new ProductResponseDto(product);
	}


	/*
		상품 정보 수정 메서드
	*/
	@Transactional
	public ProductResponseDto editProduct(AuthUser authUser, Long productId, ProductRequestDto productRequest) {

		User user = userFinder.findByUserId(authUser.getId());

		// 수정하려는 상품 찾기 -> productFinder 에서 없는 상품일경우 예외처리
		Product product = productFinder.findProductById(productId);

		// 다른 유저의 상품 수정을 방지하는 기능
		if (!(productChecker.isMyProduct(user, product))) {
			throw new ProductNotYourException();
		}

		// 상품 수정
		product.updateProduct(productRequest);

		productWriter.saveProduct(product);

		return new ProductResponseDto(product);

	}


	/*
		상품 판매상태 변경 메서드
	*/
	@Transactional
	public ProductResponseDto setSellStatus(AuthUser authUser, Long productId,
		ProductSellStatusRequestDto productSellStatusRequestDto) {

		SellStatus requestSellStatus = productSellStatusRequestDto.getSellStatus();

		User user = userFinder.findByUserId(authUser.getId());

		// 수정하려는 상품 찾기 -> productFinder 에서 없는 상품일경우 예외처리
		Product product = productFinder.findProductById(productId);

		// 다른 유저의 상품 수정을 방지하는 기능
		if (!(productChecker.isMyProduct(user, product))) {
			throw new ProductNotYourException();
		}

		if ((productChecker.isSameSellStatus(product, requestSellStatus))) {
			throw new ProductSellStatusSameFlag();
		}

		product.updateSellStatus(requestSellStatus);
		productWriter.saveProduct(product);

		return new ProductResponseDto(product);
	}


	/*
		끌어올리기 기능
	*/
	@Transactional
	public ProductResponseDto pullProduct(AuthUser authUser, Long productId){

		User user = userFinder.findByUserId(authUser.getId());

		// 끌어올리기 상품 찾기 -> productFinder 에서 없는 상품일경우 예외처리
		Product product = productFinder.findProductById(productId);

		// 다른 유저의 상품을 끌어올리는것을 방지
		if (!(productChecker.isMyProduct(user, product))) {
			throw new ProductNotYourException();
		}

		// 예외처리 로직 통과후 본격적인 동시성 처리
		Semaphore semaphore = new Semaphore(1); // 이진 세마포어

		SyncPullAt syncPullAt = new SyncPullAt(semaphore, product);
		syncPullAt.run(); // 임계구역 진입

		return new ProductResponseDto(product);
	}

	// 가입 시 등록된 위치로 부터 1km 반경 안에 있는 product 조회 메서드
	public Page<ProductResponseDto> findNearbyProductsByUserAddress(Long userId, double radius, Pageable pageable) {
		User user = userFinder.findByUserId(userId);
		GeoCoordinate userLocation = geoService.getGeoData(user.getAddress());

		Point location = GeoUtil.createPoint(userLocation.getLongitude(), userLocation.getLatitude());
		Page<Product> nearbyProducts = productFinder.findNearbyProductsByLocation(location, radius, pageable);
		return nearbyProducts.map(ProductResponseDto::new);
	}
}
