package com.dyh.service;

import com.baomidou.mybatisplus.extension.api.R;
import com.baomidou.mybatisplus.extension.service.IService;
import com.dyh.entity.PPost;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * 冒泡/文章(PPost)表服务接口
 *
 * @author makejava
 * @since 2022-11-20 12:35:24
 */
public interface PPostService extends IService<PPost> {

    R getById(Long id) throws JsonProcessingException;

}


