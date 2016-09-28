/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.rest.webmvc.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.aspectj.lang.annotation.Aspect;
import org.junit.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.rest.tests.CommonWebTests;
import org.springframework.data.rest.webmvc.util.InterleavingThreadScheduler;
import org.springframework.hateoas.Link;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;

/**
 * Web Integration tests for DATAREST-895.
 *
 * @author Laurent Bovet
 */
@ContextConfiguration(classes = {DataRest895Tests.Config.class, JpaRepositoryConfig.class})
public class DataRest895Tests extends CommonWebTests {

    private static final MediaType TEXT_URI_LIST = MediaType.valueOf("text/uri-list");
    private static InterleavingThreadScheduler scheduler;

    @Configuration
    @EnableAspectJAutoProxy
    public static class Config {
        @Aspect
        public static class ThreadSchedulerAspect {
            @org.aspectj.lang.annotation.Before("execution(public * org.springframework.data.repository.CrudRepository+.*(..))")
            public void beforeCrudOperation() throws JsonProcessingException {
                if (scheduler != null) {
                    scheduler.next();
                }
            }
        }

        @Bean
        public ThreadSchedulerAspect aspect() {
            return new ThreadSchedulerAspect();
        }
    }

    static ObjectMapper mapper = new ObjectMapper();

    /*
     * (non-Javadoc)
     * @see org.springframework.data.rest.webmvc.AbstractWebIntegrationTests#expectedRootLinkRels()
     */
    @Override
    protected Iterable<String> expectedRootLinkRels() {
        return Arrays.asList("people", "authors", "books");
    }

    @Test
    public void addSiblingsConcurrently() throws Exception {

        List<Link> links = preparePersonResources(new Person("Frodo", "Baggins"), //
                new Person("Bilbo", "Baggins"), //
                new Person("Merry", "Baggins"), //
                new Person("Pippin", "Baggins"));

        Link frodosSiblingsLink = links.get(0);

        scheduler = new InterleavingThreadScheduler();
        scheduler.start(new Runnable[]{
                        () -> addSibling(frodosSiblingsLink, links.get(1)),
                        () -> addSibling(frodosSiblingsLink, links.get(2)),
                        () -> addSibling(frodosSiblingsLink, links.get(3))
                },
                new Integer[][]{{0, 1}}); // First thread will run through the first two operations without giving hand
        scheduler = null;

        assertSiblingNames(frodosSiblingsLink, "Bilbo", "Merry", "Pippin");
    }

    private void addSibling(Link owner, Link sibling) {
        try {
            patchAndGet(owner, sibling.getHref(), TEXT_URI_LIST);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Link> preparePersonResources(Person primary, Person... persons) throws Exception {

        Link peopleLink = client.discoverUnique("people");
        List<Link> links = new ArrayList<Link>();

        MockHttpServletResponse primaryResponse = postAndGet(peopleLink, mapper.writeValueAsString(primary),
                MediaType.APPLICATION_JSON);
        links.add(client.assertHasLinkWithRel("siblings", primaryResponse));

        for (Person person : persons) {

            String payload = mapper.writeValueAsString(person);
            MockHttpServletResponse response = postAndGet(peopleLink, payload, MediaType.APPLICATION_JSON);

            links.add(client.assertHasLinkWithRel(Link.REL_SELF, response));
        }

        return links;
    }

    /**
     * Asserts the {@link Person} resource the given link points to contains siblings with the given names.
     *
     * @param link
     * @param siblingNames
     * @throws Exception
     */
    private void assertSiblingNames(Link link, String... siblingNames) {

        String responseBody = null;
        try {
            responseBody = client.request(link).getContentAsString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        List<String> persons = JsonPath.read(responseBody, "$._embedded.people[*].firstName");

        assertThat(persons, hasSize(siblingNames.length));
        assertThat(persons, hasItems(siblingNames));
    }
}
