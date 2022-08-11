package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.awt.image.RasterFormatException;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021 -12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //未开始
            return Result.fail("优惠活动尚未开始");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //结束
            return Result.fail("优惠活动已结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock()<1) {

            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        //intern使得同一个id的string值一致
        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        boolean isLock = lock.tryLock(1200);
        if (!isLock) {
            //获取锁失败,返回错误
            return Result.fail("一人只允许下一单");
        }
        try {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucher(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }

    }

    /**
     * 同一个类中方法调用，导致@Transactional失效
     * 开发中避免不了会对同一个类里面的方法调用，
     * 比如有一个类Test，它的一个方法A，A再调用本类的方法B（不论方法B是用public还是private修饰），
     * 但方法A没有声明注解事务，而B方法有。则外部调用方法A之后，方法B的事务是不会起作用的。
     * 这也是经常犯错误的一个地方
     */

    /**
     *把一整个代码块封装成一个函数 --> ctrl+alt+M
     */
    @Override
    @Transactional
    public Result createVoucher(Long voucherId) {

            Long userId = UserHolder.getUser().getId();

            //5.一人一单
            //5.1查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

            //5.2判断是否存在
            if (count > 0) {
                return Result.fail("只允许购买一次!");
            }


            //6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock-1")
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    //where id = ? and stock > 0
                    .update();
            if (!success) {
                return Result.fail("库存不足");
            }


            //7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();

            //7.1订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);

            //7.2用户id
            voucherOrder.setUserId(userId);

            //7.3代金券id
            voucherOrder.setVoucherId(voucherId);

            //7.4 保存到数据库
            save(voucherOrder);

            //8.返回订单id
            return Result.ok(orderId);

            //自动释放锁,然后提交事务,所以得把锁设在函数上

    }
}
