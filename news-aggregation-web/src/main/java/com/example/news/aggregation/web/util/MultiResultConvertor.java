package com.example.news.aggregation.web.util;

import com.example.news.aggregation.base.response.PageResponse;
import com.example.news.aggregation.web.vo.MultiResult;

import static com.example.news.aggregation.base.response.ResponseCode.SUCCESS;

/**
 * @author Hollis
 */
public class MultiResultConvertor {

    public static <T> MultiResult<T> convert(PageResponse<T> pageResponse) {
        MultiResult<T> multiResult = new MultiResult<T>(true, SUCCESS.name(), SUCCESS.name(), pageResponse.getDatas(), pageResponse.getTotal(), pageResponse.getCurrentPage(), pageResponse.getPageSize());
        return multiResult;
    }
}
