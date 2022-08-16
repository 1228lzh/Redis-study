package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021 -12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<Long>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 阻塞队列
     */
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    /**
     * 线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private static IVoucherOrderService proxy;
    /**
     * 线程任务
     */
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();

                    //2.创建真的订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    /**
     * 外部类
     * @param voucherOrder 订单
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户id
        Long userId = voucherOrder.getUserId();
        //intern使得同一个id的string值一致
        //2.创建锁对象
        RLock lock = redissonClient.getLock("order" + userId);

        //3.获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            //3.1获取锁失败,返回错误
            log.error("一人限购一单");
            return;
        }
        try {
            proxy.createVoucher(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    /**
     * PostConstruct注解 在当前类初始化完毕后就来执行这个东西
     */
    @PostConstruct
    private void init(){
        //执行线程任务
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.判断条件
        //1.1查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //1.2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //未开始
            return Result.fail("优惠活动尚未开始");
        }
        //1.3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //结束
            return Result.fail("优惠活动已结束");
        }

        Long userId = UserHolder.getUser().getId();
        //2. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                //空集合
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //3.判断结果是否为0
        int r = result.intValue();

        if (r != 0){
            //3.1 不为0,代表没有购买资格,=1,2
            return Result.fail(r==1?"库存不足":"一人限购一单");
        }

        //3.2 为0,有购买资格,保存下单信息到阻塞队列中

        //4.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        //4.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        //4.2用户id
        voucherOrder.setUserId(userId);

        //4.3代金券id
        voucherOrder.setVoucherId(voucherId);

        //4.4获取代理对象(事务)
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //5.添加订单消息到阻塞队列
        orderTasks.add(voucherOrder);

        //6.返回订单id
        return Result.ok(orderId);

    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //未开始
//            return Result.fail("优惠活动尚未开始");
//        }
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //结束
//            return Result.fail("优惠活动已结束");
//        }
//        //4.判断库存是否充足
//        if (voucher.getStock()<1) {
//
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        //intern使得同一个id的string值一致
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order" + userId);
//
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            //获取锁失败,返回错误
//            return Result.fail("一人只允许下一单");
//        }
//        try {
//            //获取代理对象(事务)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucher(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//
//    }

    /*
      同一个类中方法调用，导致@Transactional失效
      开发中避免不了会对同一个类里面的方法调用，
      比如有一个类Test，它的一个方法A，A再调用本类的方法B（不论方法B是用public还是private修饰），
      但方法A没有声明注解事务，而B方法有。则外部调用方法A之后，方法B的事务是不会起作用的。
      这也是经常犯错误的一个地方
     */


//     把一整个代码块封装成一个函数 --> ctrl+alt+M


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucher(VoucherOrder voucherOrder) {

        Long userId = voucherOrder.getUserId();

        //5.一人一单
        //5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();

        //5.2判断是否存在
        if (count > 0) {
            log.error("用户已购买过一次");
            return;
        }


        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                //where id = ? and stock > 0
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }


        //7.保存到数据库
        save(voucherOrder);

        //自动释放锁,然后提交事务,所以得把锁设在函数上

    }
}
