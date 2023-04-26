package com.dyh.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.api.R;
import com.baomidou.mybatisplus.extension.enums.ApiErrorCode;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dyh.dao.PPostDao;
import com.dyh.entity.PPost;
import com.dyh.entity.PPostContent;
import com.dyh.entity.PTag;
import com.dyh.entity.PTagPost;
import com.dyh.entity.dto.PPostContentDTO;
import com.dyh.entity.po.PFollow;
import com.dyh.entity.po.PUser;
import com.dyh.entity.vo.PPostCreateVo;
import com.dyh.entity.vo.PPostDetailVo;
import com.dyh.entity.vo.PPostDisplayVo;
import com.dyh.entity.vo.PPostLikeRankVo;
import com.dyh.feign.PUserFeignService;
import com.dyh.service.*;
import com.dyh.utils.RedisIdWorker;
import com.dyh.utils.UserHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.dyh.constant.PostConstants.POST_CONTENT_PREFIX;
import static com.dyh.constant.PostConstants.POST_PREFIX;
import static com.dyh.constant.RedisConstants.*;

/**
 * 冒泡/文章(PPost)表服务实现类
 *
 * @author makejava
 * @since 2022-11-20 12:35:24
 */
@Service("pPostService")
public class PPostServiceImpl extends ServiceImpl<PPostDao, PPost> implements PPostService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    ObjectMapper objectMapper;

    @Resource
    PUserFeignService pUserFeignService;

    @Resource
    PPostContentService pPostContentService;

    @Resource
    PTagService pTagService;

    @Resource
    PTagPostService pTagPostService;

    @Resource
    PPostCollectionService postCollectionService;

    @Resource
    RedisIdWorker redisIdWorker;


    /**
     * 由于被继承的方法返回值为泛型不好擦除于是重新构造并且实现接口方法
     *
     * @param id id
     * @return {@link R}
     */
    @Override
    public R getById(Long id) throws JsonProcessingException {
        String key=CACHE_POST_KEY+id;

        // 1.从redis查询文章缓存
        String postJson= stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(postJson)){
            try {
                PPost pPost=objectMapper.readValue(postJson,PPost.class);
                // 3.存在，直接返回
                return R.ok(pPost);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }
        // 判断命中的是否是空值
        if(postJson!=null){
            // 返回一个错误信息
            R.failed("缓存错误");
        }

        // 4.不存在则根据id查询数据库
        PPost pPost= super.getById(id);
        // 5.不存在，返回错误
        if(pPost==null){
            // 将空值写入到redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            // 返回错误信息
            return R.failed("文章不存在！");
        }
        // 6.存在，写入redis，设置过期时间
        stringRedisTemplate.opsForValue().set(key,objectMapper.writeValueAsString(pPost),CACHE_POST_TTL, TimeUnit.MINUTES);
        // 7.返回
        return R.ok(pPost);
    }

    /**
     * 文章首页显示
     *
     * @return {@link R}
     */
    @Override
    public R pPostDisplay(Page<PPostDisplayVo> page, Wrapper<PPost> queryWrapper) {
        Page<PPost> pPostPage = new Page<>(page.getCurrent(), page.getSize());
        List<PPostDisplayVo> postVos = this.baseMapper.selectPage(pPostPage, queryWrapper)
                .getRecords().stream().map((i) ->{
                    PPostDisplayVo pPostDisplayVo = BeanUtil.copyProperties(i, PPostDisplayVo.class);
                    PUser getUser = null;
                    try {
                        getUser = objectMapper.readValue(objectMapper.writeValueAsString(pUserFeignService.selectOne(pPostDisplayVo.getUserId()).getData()), PUser.class);
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                    if(getUser==null){
                        pPostDisplayVo.setNickname("default");
                        pPostDisplayVo.setAvatar("default");
                        return pPostDisplayVo;
                    }
                    pPostDisplayVo.setNickname(getUser.getNickname());
                    pPostDisplayVo.setAvatar(getUser.getAvatar());
                    return pPostDisplayVo;
                }).collect(Collectors.toList());
        page.setTotal(pPostPage.getTotal());
        page.setPages(pPostPage.getPages());
        page.setRecords(postVos);
        return R.ok(page);
    }


    @Override
    public R createPost(PPostCreateVo pPostCreateVo) throws JsonProcessingException {
        Long userId = UserHolder.getUser().getId();

        // 1.为创建的文章生成分布式ID
        long postId = redisIdWorker.nextId(POST_PREFIX);
        // 2.将vo中需要的内容取出
        List<PPostContentDTO> pPostContentDTOs=pPostCreateVo.getContents();
        List<String> tags=pPostCreateVo.getTags();

        // 3.将文章内容存储到数据库
        pPostContentDTOs.forEach((i)->{
            PPostContent pPostContent = BeanUtil.copyProperties(i, PPostContent.class);
            pPostContent.setPostId(postId);
            pPostContent.setUserId(userId);
            pPostContent.setId(redisIdWorker.nextId(POST_CONTENT_PREFIX));
            // 保存文章内容到数据库
            pPostContentService.save(pPostContent);
        });

        // 4.将tag关联存储到数据库
        for (String tag : tags) {
            R res = pTagService.getByTag(tag);
            if (res.getCode() == ApiErrorCode.FAILED.getCode()) {// 如果在查询中出错则直接返回错误信息
                return res;
            }
            // 反序列化得到PTag完整信息
            PTag getPTag = objectMapper.readValue(objectMapper.writeValueAsString(res.getData()), PTag.class);
            // 创建并且赋值需要的关联信息
            PTagPost pTagPost = new PTagPost();
            pTagPost.setPostId(postId);
            pTagPost.setTagId(getPTag.getId());
            // 存储关联信息
            pTagPostService.save(pTagPost);
        }

        PPost pPost=new PPost();
        pPost.setId(postId);
        pPost.setUserId(userId);
        pPost.setAttachmentPrice(pPostCreateVo.getAttachmentPrice());
        pPost.setSummary(pPostCreateVo.getSummary());
        pPost.setTags(listToString(tags));
        // 5.保存文章信息
        boolean isSuccess = save(pPost);
        if(!isSuccess){
            return R.failed("新增博文失败!");
        }
        // 6.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<PFollow> follows = pUserFeignService.queryFansById(userId).getData();
        // 7.推送博文id给所有粉丝
        for (PFollow follow : follows) {
            // 7.1.获取粉丝id
            Long fanId = follow.getUserId();
            // 7.2.推送
            String key = FEED_KEY + fanId;
            stringRedisTemplate.opsForZSet().add(key, pPost.getId().toString(), System.currentTimeMillis());
        }

        return R.ok(postId);
    }

    @Override
    public R pPostDetail(Long id) {
        // 1.通过id查询到文章
        PPost getPPost = this.baseMapper.selectById(id);
        if(getPPost==null){
            return R.failed("查询不到该文章");
        }
        // 2.提取组装文章基本信息
        PPostDetailVo pPostDetailVo = BeanUtil.copyProperties(getPPost, PPostDetailVo.class);
        // 3.获取文章内容信息
        R<List<PPostContent>> res = pPostContentService.getByPostId(id);
        if(res.getCode()==ApiErrorCode.FAILED.getCode()){// 如果获取文章内容失败
            return res;
        }
        List<PPostContent> contents = res.getData();
        // 4.将文章内容转换成DTO
        List<PPostContentDTO> contentDTOS = contents.stream().map(i -> BeanUtil.copyProperties(i, PPostContentDTO.class)).collect(Collectors.toList());
        pPostDetailVo.setContents(contentDTOS);

        PUser getUser = null;
        try {
            getUser = objectMapper.readValue(objectMapper.writeValueAsString(pUserFeignService.selectOne(getPPost.getUserId()).getData()), PUser.class);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        if(getUser==null){
            return R.failed("未找到该作者");
        }
        pPostDetailVo.setAvatar(getUser.getAvatar());
        pPostDetailVo.setNickname(getUser.getNickname());
        // 5.返回文章细节
        return R.ok(pPostDetailVo);
    }

    @Override
    public R likePost(Long postId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = POST_LIKED_KEY + postId;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if(BooleanUtil.isFalse(isMember)){
            //3.如果未点赞，可以点赞
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("upvote_count = upvote_count + 1").eq("id", postId).update();
            //3.2 保存用户到Redis的set集合
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }
        }else{
            //4.如果已点赞，取消点赞
            //4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("upvote_count = upvote_count - 1").eq("id", postId).update();
            //4.2 把用户从Redis的set集合移除
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,userId.toString());
            }
            PPost getPPost = this.baseMapper.selectOne(new QueryWrapper<PPost>().select("upvote_count").eq("id", postId));
            return R.ok(getPPost.getUpvoteCount()).setMsg("取消点赞");
        }

        PPost getPPost = this.baseMapper.selectOne(new QueryWrapper<PPost>().select("upvote_count").eq("id", postId));
        return R.ok(getPPost.getUpvoteCount()).setMsg("成功点赞");
    }

    @Override
    public R postLikeRank() {
        // 1.首先从redis中查询出榜单的前十名
        Set<String> postIds = stringRedisTemplate.opsForZSet().reverseRange(POST_LIKE_RANK_KEY, 0, 9);

        List<PPostLikeRankVo> pPostLikeRankVos=new ArrayList<>();
        if(CollectionUtils.isEmpty(postIds)){
            // 2.redis中不存在 从数据库中读取
            List<PPostLikeRankVo> getPPostLikeRankVos = this.baseMapper.selectPostLikeRankInTen();
            if(CollectionUtils.isEmpty(getPPostLikeRankVos)){
                return R.failed("文章集为空");
            }
            // 2.1.给redis补上缓存的排名数据
            getPPostLikeRankVos.forEach((i)->{
                stringRedisTemplate.opsForZSet().add(POST_LIKE_RANK_KEY,i.getId().toString(),i.getUpvoteCount());
            });
            pPostLikeRankVos=getPPostLikeRankVos;
        }else{
            // 3.redis中存在 根据取出的 postIds 组装内容
            long sequence=0L;
            for (String postId:
                 postIds) {
                PPostLikeRankVo getPPostLikeRankVo = this.baseMapper.selectPPostLikeRankVoById(Long.valueOf(postId));
                if(getPPostLikeRankVo==null){
                    // 3.1.如果该文章在数据库中不存在，则在redis中移除
                    stringRedisTemplate.opsForZSet().remove(POST_LIKE_RANK_KEY, postId);
                    continue;
                }
                getPPostLikeRankVo.setSequence(++sequence);
                pPostLikeRankVos.add(getPPostLikeRankVo);
            }
        }

        return R.ok(pPostLikeRankVos);
    }

    @Override
    public R<List<PPost>> selectBatch(List<Long> idList) {
        String idStr = StrUtil.join(",", idList);
        List<PPost> pPosts = query().in("id", idList).last("ORDER BY FIELD(id," + idStr + ")").list();
        return R.ok(pPosts);
    }

    private String listToString(List<String> list){
        if(list.size()==0){
            return "";
        }
        StringBuilder res=new StringBuilder();
        res.append(list.get(0));
        for (int i = 1; i < list.size(); i++) {
            res.append(",");
            res.append(list.get(i));
        }
        return res.toString();
    }

}

