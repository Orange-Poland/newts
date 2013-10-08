package org.opennms.newts.rest;


import static spark.Spark.get;
import static spark.Spark.post;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.ObjectReader;
import org.codehaus.jackson.type.TypeReference;
import org.opennms.newts.api.MeasurementRepository;
import org.opennms.newts.api.Measurement;
import org.opennms.newts.api.Metric;
import org.opennms.newts.api.Results;
import org.opennms.newts.api.Results.Row;
import org.opennms.newts.api.Timestamp;

import spark.Request;
import spark.Response;
import spark.Route;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.inject.Guice;
import com.google.inject.Injector;


public class Server {

    private Function<Row, Collection<MeasurementDTO>> m_rowFunc = new Function<Row, Collection<MeasurementDTO>>() {

        @Override
        public Collection<MeasurementDTO> apply(Row input) {
            return Collections2.transform(input.getMeasurements(), m_toMeasurementDTO);
        }
    };

    private Function<Measurement, MeasurementDTO> m_toMeasurementDTO = new Function<Measurement, MeasurementDTO>() {

        @Override
        public MeasurementDTO apply(Measurement input) {
            MeasurementDTO output = new MeasurementDTO();
            output.setResource(input.getResource());
            output.setTimestamp(input.getTimestamp().asMillis());
            output.setValue(input.getValue());
            output.setName(input.getMetric().getName());
            output.setType(input.getMetric().getType());
            return output;
        }
    };

    private Function<MeasurementDTO, Measurement> m_fromMeasurementDTO = new Function<MeasurementDTO, Measurement>() {

        @Override
        public Measurement apply(MeasurementDTO m) {
            Metric metric = new Metric(m.getName(), m.getType());
            return new Measurement(new Timestamp(m.getTimestamp()), m.getResource(), metric, m.getValue());
        }
    };

    private final MeasurementRepository m_repository;

    @Inject
    public Server(final MeasurementRepository repository) {
        m_repository = repository;
        initialize();
    }

    private void initialize() {

        post(new Route("/") {

            @Override
            public Object handle(Request request, Response response) {

                ObjectMapper mapper = new ObjectMapper();
                ObjectReader reader = mapper.reader(new TypeReference<List<MeasurementDTO>>() {});
                Collection<MeasurementDTO> measurementDTOs = null;

                try {
                    measurementDTOs = reader.readValue(request.body());
                }
                catch (IOException e) {
                    halt(400, String.format("Unable to parse request body as JSON (reason: %s) ", e.getMessage()));
                }

                m_repository.insert(Collections2.transform(measurementDTOs, m_fromMeasurementDTO));

                return "";
            }
        });

        get(new JsonTransformerRoute<Object>("/:resource") {

            @Override
            public Object handle(Request request, Response response) {

                String resource = request.params(":resource");
                String startParam = request.queryParams("start");
                String endParam = request.queryParams("end");

                Timestamp start = null, end = null;

                if (startParam != null) {
                    try {
                        start = new Timestamp(Integer.parseInt(startParam), TimeUnit.MILLISECONDS);
                    }
                    catch (NumberFormatException e) {
                        halt(400, "Invalid start parameter");
                    }
                }

                if (endParam != null) {
                    try {
                        end = new Timestamp(Integer.parseInt(endParam), TimeUnit.MILLISECONDS);
                    }
                    catch (NumberFormatException e) {
                        halt(400, "Invalid end parameter");
                    }
                }

                Results select = m_repository.select(resource, start, end);
                
                return Collections2.transform(select.getRows(), m_rowFunc);
            }
        });

    }

    public static void main(String... args) {

        Injector injector = Guice.createInjector(new Config());
        injector.getInstance(Server.class);

    }

}
