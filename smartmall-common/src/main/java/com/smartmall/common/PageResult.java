package com.smartmall.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;
    private long current;
    private long size;
    private long total;
    private long pages;
}
