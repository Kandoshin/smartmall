package com.smartmall.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@MapperScan("com.smartmall.user.mapper")
@SpringBootApplication
public class SmartmallUserServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmartmallUserServiceApplication.class, args);
	}

}
