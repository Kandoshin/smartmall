package com.smartmall.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@AutoConfigureMockMvc
class SmartmallUserServiceApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {

	}

	@Test
	void shouldReturnUserCount() throws Exception {
		mockMvc.perform(get("/users/count"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(200));
	}

	@Test
	void shouldRejectBlankUsername() throws Exception {
		mockMvc.perform(post("/users")
				.contentType("application/json")
				.content("""
						{
						"username": "",
						"email": "alice@example.com"
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(400))
				.andExpect(jsonPath("$.message").value("用户名不能为空"));


	}

	@Test
	void shouldReturnNotFoundWhenUserDoesNotExist() throws Exception {
		mockMvc.perform(get("/users/99999999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(404))
				.andExpect(jsonPath("$.message")
						.value("用户不存在，id = 99999999"));
	}

	@Test
	void shouldReturnPaginatedUsers() throws Exception {
		mockMvc.perform(get("/users")
						.param("page", "1")
						.param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.current").value(1))
				.andExpect(jsonPath("$.data.size").value(1))
				.andExpect(jsonPath("$.data.records").isArray())
				.andExpect(jsonPath("$.data.total").isNumber())
				.andExpect(jsonPath("$.data.pages").isNumber());
	}

	@Test
	void shouldRejectPageSizeAbove100() throws Exception {
		mockMvc.perform(get("/users")
						.param("size", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(400));
	}

	@Test
	void shouldRejectPageZero() throws Exception {
		mockMvc.perform(get("/users")
						.param("page", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(400));
	}


}
