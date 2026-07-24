package health.emrys.afon.acceptance.azure

import uk.co.odinconsultants.documentation_utils.Datum

import java.lang.reflect.Field

trait AzureTableCreation {
  def createDatumTable(tableName: String): String = {
    val fields: String = classOf[Datum].getDeclaredFields
      .map { field: Field =>
        s"${field.getName} ${field.getType.getSimpleName}"
      }
      .mkString(",\n")
    s"""CREATE TABLE $tableName ($fields) USING iceberg""".stripMargin
  }
}
