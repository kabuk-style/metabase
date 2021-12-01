import React from "react";
import { ROOT_COLLECTION } from "metabase/entities/collections";
import Section, { SectionHeader, SectionTitle } from "../LandingSection";

const OurAnalyticsSection = () => {
  return (
    <Section>
      <SectionHeader>
        <SectionTitle>{ROOT_COLLECTION.name}</SectionTitle>
      </SectionHeader>
    </Section>
  );
};

export default OurAnalyticsSection;
