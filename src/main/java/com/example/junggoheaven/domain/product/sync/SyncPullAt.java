package com.example.junggoheaven.domain.product.sync;

import com.example.junggoheaven.domain.product.entity.Product;
import java.util.concurrent.Semaphore;

public class SyncPullAt implements Runnable {

	Semaphore semaphore;
	Product product;


	public SyncPullAt(Semaphore semaphore, Product product) {
		super();
		this.semaphore = semaphore;
		this.product = product;
	}


	@Override
	public void run() {

		try {
			semaphore.acquire(); // 임계구역 설정

			product.pullProduct();

			semaphore.release(); // 임계구역 해제

		} catch (Exception e) {
			System.out.println("세마포어 오류");
		}

	}
}
