package com.exchange.matching.model;

public final class Order
{
    private long orderId;
    private String symbol;
    private Side side;
    private OrderType type;
    private long price;
    private long quantity;
    private long remainingQty;
    private long timestampNanos;
    private OrderStatus status;

    public Order() 
    {

    }

    public Order reset(long orderId, String symbol, Side side, OrderType type, long price, long quantity, long timestampNanos)
    {
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.timestampNanos = timestampNanos;
        this.remainingQty = quantity;
        this.status = OrderStatus.OPEN; 
        return this;
    }


    public long getOrderId()
    {
        return orderId;
    }

    public String getSymbol()
    {
        return symbol;
    }

    public Side getSide()
    {
        return side;
    }

    public OrderType getType()
    {
        return type;
    }

    public long getPrice()
    {
        return price;
    }
    public long getQuantity()
    {
        return quantity;
    }
    public long getRemainingQty()
    {
        return remainingQty;
    }
    public long getTimestampNanos()
    {
        return timestampNanos;
    }

    public void setRemainingQty(long remainingQty)
    {
        this.remainingQty = remainingQty;
    }

    public void setStatus(OrderStatus status)
    {
        this.status = status;
    }

}

