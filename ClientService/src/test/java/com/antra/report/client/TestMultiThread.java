package com.antra.report.client;

import com.antra.report.client.pojo.reponse.ExcelResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

public class TestMultiThread {
    @Test
    public void testCF(){
        ExcelResponse excelResponse = new ExcelResponse();
        CompletableFuture<ExcelResponse> cf = CompletableFuture.supplyAsync(
                ()-> {
                    excelResponse.setFileId("11");
                    return excelResponse;
                })
        .exceptionally(e->{
            System.out.println("error");
            excelResponse.setFileId("22");
            return excelResponse;
        }).whenComplete((s,f)-> System.out.println(excelResponse.getFileId()));
    }

}
