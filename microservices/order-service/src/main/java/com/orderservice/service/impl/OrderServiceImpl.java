package com.orderservice.service.impl;

import com.orderservice.dto.request.OrderPlaceDto;
import com.orderservice.dto.request.OrderRequest;
import com.orderservice.dto.response.InventoryResponse;
import com.orderservice.entity.Order;
import com.orderservice.exception.NotProductFoundException;
import com.orderservice.exception.NotStockFoundException;
import com.orderservice.repository.OrderRepository;
import com.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
@Transactional
public class OrderServiceImpl implements OrderService {

    private static final String debugId = "43a37753-bad2-4eae-af3b-3a28269d45c5";

    private final OrderRepository orderRepository;
    private final ModelMapper modelMapper;
    private final WebClient.Builder webClientBuilder;

    @Override
    public void placeOrder(OrderPlaceDto orderPlaceDto){

        var isExistAllOrders = orderPlaceDto.getOrderLineItemsList()
                .stream()
                .map(orderLineItemsDto -> {
                    var response = webClientBuilder.build().get()
                            .uri("http://product-service/api/product/",
                                    uriBuilder -> uriBuilder.path(orderLineItemsDto.getSkuCode()).build())
                            .retrieve()
                            .bodyToMono(Boolean.class)
                            .block();
                    assert response != null;
                    return response;
                }).toList();

        if(isExistAllProducts(isExistAllOrders)){
                var isInStockAllProduct = orderPlaceDto.getOrderLineItemsList()
                        .stream()
                        .map(orderLineItemsDto -> {
                            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
                            queryParams.add("skuCode", orderLineItemsDto.getSkuCode());
                            queryParams.add("quantity", orderLineItemsDto.getQuantity().toString());
                            var response =  webClientBuilder.build().get()
                                    .uri("http://inventory-service/api/inventory/",
                                            uriBuilder -> uriBuilder.queryParams(queryParams).build())
                                    .retrieve()
                                    .bodyToMono(InventoryResponse.class)
                                    .block();
                            assert response != null;
                            return response;
                        }).toList();

                if(isAllProductHasStock(isInStockAllProduct)){
                    var order = orderRepository.save(modelMapper.map(orderPlaceDto,Order.class));
                    log.warn(String.format("Order placed successfully. Order id is -> %s " ,order.getId()));
                    orderPlaceDto.getOrderLineItemsList()
                            .forEach(orderLineItemsDto ->{
                                var request = OrderRequest.builder()
                                        .skuCode(orderLineItemsDto.getSkuCode())
                                        .quantity(orderLineItemsDto.getQuantity())
                                        .build();
                                webClientBuilder.build().post()
                                        .uri("http://inventory-service/api/inventory/")
                                        .body(Mono.just(request),OrderRequest.class)
                                        .retrieve()
                                        .bodyToMono(Void.class)
                                        .block();
                            });
                }
                else
                    throw new NotStockFoundException("This order list one or many product has not enough quantity for this order. " , "Not Enough Quantity" , debugId );
        }
        else
            throw new NotProductFoundException("This order list one or many product has not in database. " , "Not Product" , debugId );


    }

    private boolean isAllProductHasStock(List<InventoryResponse> isInStockAllProduct) {
        return isInStockAllProduct.stream().allMatch(inventoryResponse -> inventoryResponse.getIsInStock().equals(Boolean.TRUE));
    }

    private boolean isExistAllProducts(List<Boolean> isExistAllOrders) {
        return isExistAllOrders.stream().allMatch(aBoolean -> aBoolean.equals(Boolean.TRUE));
    }




}
