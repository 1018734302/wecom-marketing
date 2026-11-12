package com.entwc.marketing.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.entwc.marketing.common.ApiResponse;
import com.entwc.marketing.common.PageResult;
import com.entwc.marketing.contact.Customer;
import com.entwc.marketing.contact.CustomerService;

/**
 * 客户查询接口（阶段一）。
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public ApiResponse<PageResult<Customer>> list(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(customerService.page(status, keyword, page, size));
    }
}
