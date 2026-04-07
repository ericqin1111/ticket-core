package com.ticket.core.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticket.core.catalog.entity.CatalogItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CatalogItemMapper extends BaseMapper<CatalogItem> {
}
