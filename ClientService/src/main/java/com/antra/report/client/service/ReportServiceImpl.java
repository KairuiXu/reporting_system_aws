package com.antra.report.client.service;

import com.amazonaws.services.s3.AmazonS3;
import com.antra.report.client.entity.ExcelReportEntity;
import com.antra.report.client.entity.PDFReportEntity;
import com.antra.report.client.entity.ReportRequestEntity;
import com.antra.report.client.entity.ReportStatus;
import com.antra.report.client.exception.RequestNotFoundException;
import com.antra.report.client.pojo.EmailType;
import com.antra.report.client.pojo.FileType;
import com.antra.report.client.pojo.reponse.ExcelResponse;
import com.antra.report.client.pojo.reponse.PDFResponse;
import com.antra.report.client.pojo.reponse.ReportVO;
import com.antra.report.client.pojo.reponse.SqsResponse;
import com.antra.report.client.pojo.request.ReportRequest;
import com.antra.report.client.repository.ReportRequestRepo;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class ReportServiceImpl implements ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportServiceImpl.class);

    private final ReportRequestRepo reportRequestRepo;
    private final SNSService snsService;
    private final AmazonS3 s3Client;
    private final EmailService emailService;
    private final RestTemplate restTemplate;

    public ReportServiceImpl(ReportRequestRepo reportRequestRepo, SNSService snsService, AmazonS3 s3Client, EmailService emailService,RestTemplate restTemplate) {
        this.reportRequestRepo = reportRequestRepo;
        this.snsService = snsService;
        this.s3Client = s3Client;
        this.emailService = emailService;
        this.restTemplate = restTemplate;
    }
    @Override
    public void deleteall(){
        reportRequestRepo.deleteAll();
    }

    private ReportRequestEntity persistToLocal(ReportRequest request) {
        request.setReqId("Req-"+ UUID.randomUUID());

        ReportRequestEntity entity = new ReportRequestEntity();
        entity.setReqId(request.getReqId());
        entity.setSubmitter(request.getSubmitter());
        entity.setDescription(request.getDescription());
        entity.setCreatedTime(LocalDateTime.now());

        PDFReportEntity pdfReport = new PDFReportEntity();
        pdfReport.setRequest(entity);
        pdfReport.setStatus(ReportStatus.PENDING);
        pdfReport.setCreatedTime(LocalDateTime.now());
        entity.setPdfReport(pdfReport);

        ExcelReportEntity excelReport = new ExcelReportEntity();
        BeanUtils.copyProperties(pdfReport, excelReport);
        entity.setExcelReport(excelReport);

        return reportRequestRepo.save(entity);
    }

    @Override
    public ReportVO generateReportsSync(ReportRequest request) {
        persistToLocal(request);
        sendDirectRequests(request);
        return new ReportVO(reportRequestRepo.findById(request.getReqId()).orElseThrow());
    }

    @HystrixCommand(fallbackMethod = "fallbackMethod", commandProperties = {@HystrixProperty(name="execution.isolation.thread.timeoutInMilliseconds", value = "2000")})
    private void sendDirectRequests(ReportRequest request) {
//        RestTemplate rs = new RestTemplate();
        ExcelResponse excelResponse = new ExcelResponse();
        PDFResponse pdfResponse = new PDFResponse();
        CompletableFuture.runAsync(() -> {
            ExcelResponse response = restTemplate.postForEntity("http://ExcelService/excel", request, ExcelResponse.class).getBody();
            BeanUtils.copyProperties(response, excelResponse);
        }).exceptionally(e -> {
            log.error("Excel Generation Error (Sync) : e", e);
            excelResponse.setReqId(request.getReqId());
            excelResponse.setFailed(true);
            return null;
        }).whenComplete((s, e) -> updateLocal(excelResponse));
        CompletableFuture.runAsync(() -> {
            PDFResponse response = restTemplate.postForEntity("http://PDFService/pdf", request, PDFResponse.class).getBody();
            BeanUtils.copyProperties(response, pdfResponse);
        }).exceptionally(e -> {
            log.error("PDF Generation Error (Sync) : e", e);
            pdfResponse.setReqId(request.getReqId());
            pdfResponse.setFailed(true);
            return null;
        }).whenComplete((s, e) -> updateLocal(pdfResponse));
//        try {
//            excelResponse = rs.postForEntity("http://localhost:8888/excel", request, ExcelResponse.class).getBody();
//        } catch(Exception e){
//            log.error("Excel Generation Error (Sync) : e", e);
//            excelResponse.setReqId(request.getReqId());
//            excelResponse.setFailed(true);
//        } finally {
//            updateLocal(excelResponse);
//        }
//        try {
//            pdfResponse = rs.postForEntity("http://localhost:9999/pdf", request, PDFResponse.class).getBody();
//        } catch(Exception e){
//            log.error("PDF Generation Error (Sync) : e", e);
//            pdfResponse.setReqId(request.getReqId());
//            pdfResponse.setFailed(true);
//        } finally {
//            updateLocal(pdfResponse);
//        }
    }
    @SuppressWarnings("unused")
    public void fallbackMethod(ReportRequest request){
        log.error("Something wrong to generate record in sync, use Async instead");
        generateReportsAsync(request);
    }

    private void updateLocal(ExcelResponse excelResponse) {
        SqsResponse response = new SqsResponse();
        BeanUtils.copyProperties(excelResponse, response);
        updateAsyncExcelReport(response);
    }
    private void updateLocal(PDFResponse pdfResponse) {
        SqsResponse response = new SqsResponse();
        BeanUtils.copyProperties(pdfResponse, response);
        updateAsyncPDFReport(response);
    }

    @Override
    @Transactional
    public ReportVO generateReportsAsync(ReportRequest request) {
        ReportRequestEntity entity = persistToLocal(request);
        snsService.sendReportNotification(request);
        log.info("Send SNS the message: {}",request);
        return new ReportVO(entity);
    }

    @Override
    @Transactional // why this? email could fail
    public void updateAsyncPDFReport(SqsResponse response) {
        ReportRequestEntity entity = reportRequestRepo.findById(response.getReqId()).orElseThrow(RequestNotFoundException::new);
        var pdfReport = entity.getPdfReport();
        pdfReport.setUpdatedTime(LocalDateTime.now());
        if (response.isFailed()) {
            pdfReport.setStatus(ReportStatus.FAILED);
        } else{
            pdfReport.setStatus(ReportStatus.COMPLETED);
            pdfReport.setFileId(response.getFileId());
            pdfReport.setFileLocation(response.getFileLocation());
            pdfReport.setFileSize(response.getFileSize());
        }
        entity.setUpdatedTime(LocalDateTime.now());
        reportRequestRepo.save(entity);
        String to = "xukairui315@gmail.com";
        emailService.sendEmail(to, EmailType.SUCCESS, entity.getSubmitter());
    }

    @Override
//    @Transactional
    public void updateAsyncExcelReport(SqsResponse response) {
        ReportRequestEntity entity = reportRequestRepo.findById(response.getReqId()).orElseThrow(RequestNotFoundException::new);
        var excelReport = entity.getExcelReport();
        excelReport.setUpdatedTime(LocalDateTime.now());
        if (response.isFailed()) {
            excelReport.setStatus(ReportStatus.FAILED);
        } else{
            excelReport.setStatus(ReportStatus.COMPLETED);
            excelReport.setFileId(response.getFileId());
            excelReport.setFileLocation(response.getFileLocation());
            excelReport.setFileSize(response.getFileSize());
        }
        entity.setUpdatedTime(LocalDateTime.now());
        reportRequestRepo.save(entity);
//        String to = "youremail@gmail.com";
        String to = "xukairui315@gmail.com";
        emailService.sendEmail(to, EmailType.SUCCESS, entity.getSubmitter());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportVO> getReportList() {
        return reportRequestRepo.findAll().stream().map(ReportVO::new).collect(Collectors.toList());
    }

    @Override
    public InputStream getFileBodyByReqId(String reqId, FileType type) {
        ReportRequestEntity entity = reportRequestRepo.findById(reqId).orElseThrow(RequestNotFoundException::new);
        if (type == FileType.PDF) {
            String fileLocation = entity.getPdfReport().getFileLocation(); // this location is s3 "bucket/key"
            String bucket = fileLocation.split("/")[0];
            String key = fileLocation.split("/")[1];
            return s3Client.getObject(bucket, key).getObjectContent();
        } else if (type == FileType.EXCEL) {
            String fileId = entity.getExcelReport().getFileId();
//            String fileLocation = entity.getExcelReport().getFileLocation();
//            try {
//                return new FileInputStream(fileLocation);// this location is in local, definitely sucks
//            } catch (FileNotFoundException e) {
//                log.error("No file found", e);
//            }
//            RestTemplate restTemplate = new RestTemplate();
//            InputStream is = restTemplate.execute(, HttpMethod.GET, null, ClientHttpResponse::getBody, fileId);
            ResponseEntity<Resource> exchange = restTemplate.exchange("http://ExcelService/excel/{id}/content",
                    HttpMethod.GET, null, Resource.class, fileId);
            try {
                return exchange.getBody().getInputStream();
            } catch (IOException e) {
                log.error("Cannot download excel",e);
            }
        }
        return null;
    }
    @Override
    @Transactional
    public void deleteReport(String reqId){
        ReportRequestEntity entity = reportRequestRepo.findById(reqId).orElseThrow(RequestNotFoundException::new);
//        RestTemplate rs = new RestTemplate();
//        ServiceInstance excelinstance = loadBalancer.choose("EXCELSERVICE");
        log.info("Send delete request to excel server");
        restTemplate.delete("http://ExcelService/excel/{id}",entity.getExcelReport().getFileId());
//        ServiceInstance pdfinstance = discoveryClient.getInstances("PDFSERVICE").get(0);
        log.info("Send delete request to pdf server");
        restTemplate.delete("http://PDFService/pdf/{id}",entity.getPdfReport().getFileId());
        reportRequestRepo.deleteById(reqId);
    }
}
