package com.antra.evaluation.reporting_system.repo;

import com.antra.evaluation.reporting_system.pojo.report.ExcelFile;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

//public interface ExcelRepository extends DynamoDBCrudRepository<ExcelFile,String> {
//}

public interface ExcelRepository extends MongoRepository<ExcelFile,String> {

}